package com.unirag.upload;

import com.unirag.infrastructure.mp.entity.UploadChunk;
import com.unirag.infrastructure.mp.entity.UploadSession;
import com.unirag.upload.support.ApiException;
import com.unirag.upload.support.TenantContext;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 分块上传会话编排（spec FR-010，research R4）：初始化校验、分块幂等写入、已收块清单、归属校验。
 * 租户隔离：会话按 (id, tenant_id) 双条件访问，跨租户一律 404 UPLOAD_NOT_FOUND（不泄漏存在性）。
 */
@Service
public class UploadSessionService {

    /** 单块大小硬上限（data-model.md 校验规则）。 */
    private static final int MAX_CHUNK_SIZE = 10 * 1024 * 1024;
    private static final int MAX_TOTAL_CHUNKS = 10000;

    private final com.unirag.infrastructure.mp.service.UploadSessionService mpSessionService;
    private final com.unirag.infrastructure.mp.service.UploadChunkService mpChunkService;
    private final UploadProperties uploadProperties;
    private final TenantContext tenantContext;

    public UploadSessionService(
            com.unirag.infrastructure.mp.service.UploadSessionService mpSessionService,
            com.unirag.infrastructure.mp.service.UploadChunkService mpChunkService,
            UploadProperties uploadProperties,
            TenantContext tenantContext) {
        this.mpSessionService = mpSessionService;
        this.mpChunkService = mpChunkService;
        this.uploadProperties = uploadProperties;
        this.tenantContext = tenantContext;
    }

    /**
     * 初始化上传会话：派生参数一致性校验（totalSize/chunkSize/totalChunks 关系与上限）。
     */
    public UploadSession create(String docName, String title, long totalSize, int chunkSize,
                                int totalChunks, String contentSha256) {
        if (docName == null || docName.isBlank() || docName.length() > 255) {
            throw new ApiException("VALIDATION_FAILED", "docName 为空或长度超过 255");
        }
        if (totalSize <= 0 || totalSize > uploadProperties.getMaxFileSize()) {
            throw new ApiException("VALIDATION_FAILED",
                    "totalSize 非法或超过单文件上限 " + uploadProperties.getMaxFileSize() + " 字节");
        }
        if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) {
            throw new ApiException("VALIDATION_FAILED", "chunkSize 非法或超过上限 " + MAX_CHUNK_SIZE + " 字节");
        }
        long expectedChunks = (totalSize + chunkSize - 1) / chunkSize;
        if (totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS || totalChunks != expectedChunks) {
            throw new ApiException("VALIDATION_FAILED",
                    "totalChunks 与 totalSize/chunkSize 派生结果不一致（应为 " + expectedChunks + "）");
        }
        if (contentSha256 == null || !contentSha256.matches("[0-9a-fA-F]{64}")) {
            throw new ApiException("VALIDATION_FAILED", "contentSha256 必须是 64 位十六进制");
        }

        Date now = new Date();
        UploadSession session = new UploadSession();
        session.setTenantId(tenantContext.getTenantId());
        session.setDocName(docName);
        session.setTitle(title);
        session.setTotalSize(totalSize);
        session.setChunkSize(chunkSize);
        session.setTotalChunks(totalChunks);
        session.setContentSha256(contentSha256.toLowerCase());
        session.setStatus("INIT");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(new Date(now.getTime() + uploadProperties.getSessionTtl().toMillis()));
        mpSessionService.save(session);
        return session;
    }

    /**
     * 分块写入：同 seq 重传 = 幂等覆盖；非尾块必须恰为 chunkSize，尾块必须恰为剩余字节数。
     */
    public List<Integer> putChunk(Long uploadId, int seq, byte[] body) {
        UploadSession session = ownedSession(uploadId);
        if (seq < 0 || seq >= session.getTotalChunks()) {
            throw new ApiException("VALIDATION_FAILED", "块序号越界: " + seq);
        }
        int expected = seq < session.getTotalChunks() - 1
                ? session.getChunkSize()
                : (int) (session.getTotalSize() - (long) session.getChunkSize() * (session.getTotalChunks() - 1));
        if (body.length != expected) {
            throw new ApiException("VALIDATION_FAILED",
                    "块 " + seq + " 大小 " + body.length + " 与期望 " + expected + " 不符");
        }

        UploadChunk chunk = new UploadChunk();
        chunk.setSessionId(uploadId);
        chunk.setSeq(seq);
        chunk.setSizeBytes(body.length);
        chunk.setData(body);
        chunk.setCreatedAt(new Date());
        // 同 seq 重传 = 覆盖（PK(session_id, seq) 裁决，断点续传幂等）
        var keyWrapper = com.baomidou.mybatisplus.core.toolkit.Wrappers.<UploadChunk>query()
                .eq("session_id", uploadId)
                .eq("seq", seq);
        if (mpChunkService.count(keyWrapper) > 0) {
            mpChunkService.update(chunk, keyWrapper);
        } else {
            mpChunkService.save(chunk);
        }
        return receivedChunks(uploadId);
    }

    /** 已收块清单（断点续传客户端只补缺块的依据）。 */
    public List<Integer> receivedChunks(Long uploadId) {
        return mpChunkService.findSeqsBySessionId(uploadId);
    }

    /** 按 seq 升序的全部已收块（完成拼装用）。 */
    public List<UploadChunk> receivedChunksFull(Long uploadId) {
        return mpChunkService.findBySessionIdOrderBySeq(uploadId);
    }

    /**
     * 归属裁决：会话不存在或属于其他租户 → 404（不泄漏存在性）；过期 → 410；非 INIT → 409。
     */
    public UploadSession ownedSession(Long uploadId) {
        UploadSession session = mpSessionService.getById(uploadId);
        if (session == null || !session.getTenantId().equals(tenantContext.getTenantId())) {
            throw new ApiException("UPLOAD_NOT_FOUND", "上传会话不存在: " + uploadId);
        }
        if ("EXPIRED".equals(session.getStatus()) || session.getExpiresAt().before(new Date())) {
            throw new ApiException("UPLOAD_EXPIRED", "上传会话已过期: " + uploadId);
        }
        if (!"INIT".equals(session.getStatus())) {
            throw new ApiException("CONCURRENT_MODIFICATION", "上传会话已完结: " + uploadId);
        }
        return session;
    }

    /** 完成/过期清理后硬删除会话与暂存块。 */
    public void deleteSessionWithChunks(Long uploadId) {
        mpChunkService.deleteBySessionId(uploadId);
        mpSessionService.removeById(uploadId);
    }

    /** 过期清理扫描（UploadSessionJanitor 用）。 */
    public List<UploadSession> findExpiredSessions() {
        return mpSessionService.findExpiredInitSessions(new Date());
    }
}

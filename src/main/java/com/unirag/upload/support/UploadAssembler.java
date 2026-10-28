package com.unirag.upload.support;

import com.unirag.common.web.ApiException;
import com.unirag.infrastructure.persistence.entity.UploadChunk;
import com.unirag.infrastructure.persistence.entity.UploadSession;
import com.unirag.upload.UploadService;
import com.unirag.upload.UploadSessionService;
import com.unirag.upload.dto.AsyncDocumentUploadResponse;
import com.unirag.upload.dto.DocumentUploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 分块上传完成编排（research R4）：收齐校验 → SHA-256 比对 → 拼装走 {@link UploadService#finalizeUpload}
 * 共用核心 → 硬删除会话与暂存块。校验失败抛 409（UPLOAD_INCOMPLETE / CHECKSUM_MISMATCH），会话保留可重试。
 */
@Slf4j
@Component
public class UploadAssembler {

    private final UploadSessionService sessionService;
    private final UploadService uploadService;

    public UploadAssembler(UploadSessionService sessionService, UploadService uploadService) {
        this.sessionService = sessionService;
        this.uploadService = uploadService;
    }

    /**
     * 完成上传：全部块到齐且摘要比对通过后落库并触发摄取。
     */
    public DocumentUploadResponse complete(Long uploadId) {
        UploadSession session = sessionService.ownedSession(uploadId);
        List<UploadChunk> chunks = chunksAscending(uploadId);

        if (chunks.size() < session.getTotalChunks()) {
            List<Integer> missing = missingChunks(session, chunks);
            throw new ApiException("UPLOAD_INCOMPLETE",
                    "尚有 " + missing.size() + " 个分块未上传", java.util.Map.of("missingChunks", missing));
        }

        byte[] assembled = assemble(session, chunks);
        String actual = sha256Hex(assembled);
        if (!actual.equalsIgnoreCase(session.getContentSha256())) {
            throw new ApiException("CHECKSUM_MISMATCH",
                    "拼装结果与初始化声明的 contentSha256 不一致，请重传全部块或新建会话");
        }

        // 与一次性上传共用核心（落 blob → 005 摄取 → 元数据）；原文名为 docName（契约 §3 无 fileName 字段）
        DocumentUploadResponse response = uploadService.finalizeUpload(
                session.getDocName(), session.getTitle(), session.getDocName() + ".txt", assembled);
        sessionService.deleteSessionWithChunks(uploadId);
        log.info("Chunked upload completed: tenantSession={}, doc={}, size={}",
                uploadId, session.getDocName(), assembled.length);
        return response;
    }

    public AsyncDocumentUploadResponse completeAsync(Long uploadId) {
        UploadSession session = sessionService.ownedSession(uploadId);
        List<UploadChunk> chunks = chunksAscending(uploadId);
        if (chunks.size() < session.getTotalChunks()) {
            List<Integer> missing = missingChunks(session, chunks);
            throw new ApiException("UPLOAD_INCOMPLETE",
                    "尚有 " + missing.size() + " 个分块未上传", java.util.Map.of("missingChunks", missing));
        }
        byte[] assembled = assemble(session, chunks);
        if (!sha256Hex(assembled).equalsIgnoreCase(session.getContentSha256())) {
            throw new ApiException("CHECKSUM_MISMATCH",
                    "拼装结果与初始化声明的 contentSha256 不一致，请重传全部块或新建会话");
        }
        AsyncDocumentUploadResponse response = uploadService.finalizeUploadAsyncChunked(
                session.getDocName(), session.getTitle(), "upload.txt", assembled);
        sessionService.deleteSessionWithChunks(uploadId);
        log.info("Chunked upload accepted: session={}, doc={}, size={}",
                uploadId, session.getDocName(), assembled.length);
        return response;
    }

    private List<UploadChunk> chunksAscending(Long uploadId) {
        return sessionService.receivedChunksFull(uploadId);
    }

    private List<Integer> missingChunks(UploadSession session, List<UploadChunk> chunks) {
        boolean[] received = new boolean[session.getTotalChunks()];
        for (UploadChunk chunk : chunks) {
            received[chunk.getSeq()] = true;
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < received.length; i++) {
            if (!received[i]) {
                missing.add(i);
            }
        }
        return missing;
    }

    private byte[] assemble(UploadSession session, List<UploadChunk> chunks) {
        byte[] assembled = new byte[session.getTotalSize().intValue()];
        int offset = 0;
        for (UploadChunk chunk : chunks) {
            System.arraycopy(chunk.getData(), 0, assembled, offset, chunk.getData().length);
            offset += chunk.getData().length;
        }
        return assembled;
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

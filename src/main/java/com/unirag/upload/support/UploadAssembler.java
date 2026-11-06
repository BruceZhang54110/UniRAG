package com.unirag.upload.support;

import com.unirag.common.web.ApiException;
import com.unirag.infrastructure.persistence.entity.UploadChunk;
import com.unirag.infrastructure.persistence.entity.UploadSession;
import com.unirag.infrastructure.storage.DocumentStorage;
import com.unirag.upload.UploadService;
import com.unirag.upload.UploadSessionService;
import com.unirag.upload.dto.AsyncDocumentUploadResponse;
import com.unirag.upload.dto.DocumentUploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.opendal.OpenDALException;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 分块上传完成编排（research R4/019 R5）：收齐校验 → SHA-256 比对 → 按 storage_path 逐块读取拼装
 * → 走 {@link UploadService#finalizeUpload} 共用核心 → 删除暂存对象与会话。块字节存 RustFS，
 * 数据库只存索引行。校验失败抛 409（UPLOAD_INCOMPLETE / CHECKSUM_MISMATCH），会话保留可重试。
 */
@Slf4j
@Component
public class UploadAssembler {

    private final UploadSessionService sessionService;
    private final UploadService uploadService;
    private final DocumentStorage documentStorage;

    public UploadAssembler(UploadSessionService sessionService, UploadService uploadService,
                           DocumentStorage documentStorage) {
        this.sessionService = sessionService;
        this.uploadService = uploadService;
        this.documentStorage = documentStorage;
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

        // 与一次性上传共用核心（落原文对象 → 005 摄取 → 元数据）；fileName 取自会话（022）
        DocumentUploadResponse response = uploadService.finalizeUpload(
                session.getDocName(), session.getTitle(), session.getFileName(), assembled);
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
                session.getDocName(), session.getTitle(), session.getFileName(), assembled);
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

    /**
     * 按 storage_path 逐块读取并拼装（019 R5）。任一块对象缺失按缺块处理（409 UPLOAD_INCOMPLETE），
     * 与"行缺失"语义一致；对象字节数与登记不符视为损坏，同样按缺块重传。
     */
    private byte[] assemble(UploadSession session, List<UploadChunk> chunks) {
        byte[] assembled = new byte[session.getTotalSize().intValue()];
        int offset = 0;
        for (UploadChunk chunk : chunks) {
            byte[] data = readChunk(session, chunk);
            System.arraycopy(data, 0, assembled, offset, data.length);
            offset += data.length;
        }
        return assembled;
    }

    private byte[] readChunk(UploadSession session, UploadChunk chunk) {
        try {
            return documentStorage.read(chunk.getStoragePath());
        } catch (OpenDALException e) {
            if (e.getCode() == OpenDALException.Code.NotFound) {
                throw incomplete(session, List.of(chunk.getSeq()));
            }
            throw e;
        }
    }

    private ApiException incomplete(UploadSession session, List<Integer> missing) {
        return new ApiException("UPLOAD_INCOMPLETE",
                "尚有 " + missing.size() + " 个分块未上传", java.util.Map.of("missingChunks", missing));
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

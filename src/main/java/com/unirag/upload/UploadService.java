package com.unirag.upload;

import com.unirag.ingestion.AsyncIngestionProperties;
import com.unirag.ingestion.IngestOutcome;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionService;
import com.unirag.ingestion.support.DocumentFingerprint;
import com.unirag.infrastructure.mp.entity.Document;
import com.unirag.infrastructure.mp.entity.DocumentIngestionJob;
import com.unirag.infrastructure.mp.service.DocumentIngestionJobService;
import com.unirag.infrastructure.mp.service.DocumentService;
import com.unirag.upload.dto.AsyncDocumentUploadResponse;
import com.unirag.upload.dto.DocumentUploadResponse;
import com.unirag.upload.support.ApiException;
import com.unirag.upload.support.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.opendal.Operator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * 上传编排（spec FR-001/FR-005/FR-006，research R4/R5/R7）：
 * 统一校验、持久化版本原文和兼容元数据；异步路径在保存后创建或复用持久化任务。
 * 一次性上传与分块拼装共用收尾校验及持久化入口。
 */
@Slf4j
@Service
public class UploadService {

    private final TenantContext tenantContext;
    private final FileTypeValidator fileTypeValidator;
    private final UploadProperties uploadProperties;
    private final Operator documentStorageOperator;
    private final DocumentService documentService;
    private final com.unirag.upload.DocumentFileService documentFileService;
    private final IngestionService ingestionService;
    private final DocumentIngestionJobService ingestionJobService;
    private final AsyncIngestionProperties asyncProperties;

    public UploadService(TenantContext tenantContext,
                         FileTypeValidator fileTypeValidator,
                         UploadProperties uploadProperties,
                         Operator documentStorageOperator,
                         DocumentService documentService,
                         DocumentFileService documentFileService,
                         IngestionService ingestionService,
                         DocumentIngestionJobService ingestionJobService,
                         AsyncIngestionProperties asyncProperties) {
        this.tenantContext = tenantContext;
        this.fileTypeValidator = fileTypeValidator;
        this.uploadProperties = uploadProperties;
        this.documentStorageOperator = documentStorageOperator;
        this.documentService = documentService;
        this.documentFileService = documentFileService;
        this.ingestionService = ingestionService;
        this.ingestionJobService = ingestionJobService;
        this.asyncProperties = asyncProperties;
    }

    /**
     * 一次性上传（小文件路径，≤ one-shot-threshold）。
     */
    public DocumentUploadResponse uploadOneShot(String docName, String title,
                                                String originalFilename, byte[] bytes) {
        validateUpload(docName, originalFilename, bytes, true);
        return finalizeUpload(docName, title, originalFilename, bytes);
    }

    /**
     * 上传收尾共用核心：落 blob → 摄取 → 落元数据。分块拼装与一次性上传共用（research R4）。
     */
    public DocumentUploadResponse finalizeUpload(String docName, String title,
                                                 String originalFilename, byte[] bytes) {
        return finalizeUploadResult(docName, title, originalFilename, bytes,
                () -> finalizeSynchronously(docName, title, originalFilename, bytes));
    }

    @Transactional
    public AsyncDocumentUploadResponse finalizeUploadAsync(String docName, String title,
                                                            String originalFilename, byte[] bytes) {
        return finalizeUploadAsync(docName, title, originalFilename, bytes, true);
    }

    @Transactional
    public AsyncDocumentUploadResponse finalizeUploadAsyncChunked(String docName, String title,
                                                                   String originalFilename, byte[] bytes) {
        return finalizeUploadAsync(docName, title, originalFilename, bytes, false);
    }

    private AsyncDocumentUploadResponse finalizeUploadAsync(String docName, String title,
                                                             String originalFilename, byte[] bytes,
                                                             boolean oneShot) {
        if (!asyncProperties.isEnabled()) {
            throw new IllegalStateException("Async ingestion is disabled");
        }
        validateUpload(docName, originalFilename, bytes, oneShot);
        String fingerprint = DocumentFingerprint.sha256(bytes);
        Document document = documentIngestionDocument(docName, title, fingerprint);
        DocumentIngestionJob existing = ingestionJobService.findByTenantDocumentAndFingerprint(
                tenantContext.getTenantId(), document.getId(), fingerprint);
        if (existing != null) {
            return toAsyncResponse(document, existing);
        }

        com.unirag.infrastructure.mp.entity.DocumentFile existingFile =
                documentFileService.findByDocumentId(document.getId());
        int proposedVersion = document.getVersion() + (fingerprint.equals(document.getContentFingerprint()) ? 0 : 1);
        String storagePath = existingFile != null && fingerprint.equals(document.getContentFingerprint())
                ? existingFile.getStoragePath()
                : storagePathOf(tenantContext.getTenantCode(), docName, proposedVersion);
        boolean wroteBlob = existingFile == null || !storagePath.equals(existingFile.getStoragePath());
        if (wroteBlob) {
            documentStorageOperator.write(storagePath, bytes);
        }
        try {
            DocumentIngestionJob job = ingestionJobService.createOrReuse(document, fingerprint,
                    storagePath, title, fileTypeValidator.sanitizeFileName(originalFilename),
                    fileTypeValidator.mimeTypeOf(originalFilename), bytes.length,
                    asyncProperties.getMaxAttempts());
            if (job == null) {
                throw new IllegalStateException("Unable to create or reuse ingestion job");
            }
            return toAsyncResponse(document, job);
        } catch (RuntimeException | Error failure) {
            if (wroteBlob) {
                try {
                    documentStorageOperator.delete(storagePath);
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    log.warn("上传持久化失败后的原文补偿删除失败: tenant={}, doc={}",
                            tenantContext.getTenantCode(), docName);
                }
            }
            throw failure;
        }
    }

    private <T> T finalizeUploadResult(String docName, String title, String originalFilename,
                                       byte[] bytes, Supplier<T> finalizer) {
        validateUpload(docName, originalFilename, bytes, false);
        String fingerprint = DocumentFingerprint.sha256(bytes);
        int version = predictedVersion(docName, fingerprint);
        String storagePath = storagePathOf(tenantContext.getTenantCode(), docName, version);
        Document existing = documentService.findByTenantAndName(tenantContext.getTenantId(), docName);
        boolean sameContent = existing != null && fingerprint.equals(existing.getContentFingerprint());
        if (!sameContent) {
            documentStorageOperator.write(storagePath, bytes);
        }
        try {
            T result = finalizer.get();
            if (!sameContent && version > 1) {
                deleteObsoleteBlob(storagePathOf(tenantContext.getTenantCode(), docName, version - 1),
                        tenantContext.getTenantCode(), docName);
            }
            return result;
        } catch (RuntimeException | Error failure) {
            try {
                documentStorageOperator.delete(storagePath);
            } catch (Exception cleanupFailure) {
                log.warn("上传持久化失败后的原文补偿删除失败: tenant={}, doc={}",
                        tenantContext.getTenantCode(), docName);
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void validateUpload(String docName, String originalFilename, byte[] bytes, boolean oneShot) {
        if (bytes == null || bytes.length > uploadProperties.getMaxFileSize()) {
            throw new ApiException("FILE_TOO_LARGE", "文件大小超过上限 " + uploadProperties.getMaxFileSize() + " 字节",
                    java.util.Map.of("limit", uploadProperties.getMaxFileSize()));
        }
        if (oneShot && bytes.length > uploadProperties.getOneShotThreshold()) {
            throw new ApiException("FILE_TOO_LARGE", "文件超过一次性上传阈值，请使用分块上传",
                    java.util.Map.of("limit", uploadProperties.getOneShotThreshold()));
        }
        validateDocName(docName);
        fileTypeValidator.validateFileName(originalFilename);
        fileTypeValidator.validateContent(bytes);
        if (new String(bytes, StandardCharsets.UTF_8).isBlank()) {
            throw new ApiException("VALIDATION_FAILED", "文件为空或仅包含空白字符");
        }
    }

    private int predictedVersion(String docName, String fingerprint) {
        Document existing = documentService.findByTenantAndName(tenantContext.getTenantId(), docName);
        return existing != null && fingerprint.equals(existing.getContentFingerprint())
                ? existing.getVersion() : existing == null ? 1 : existing.getVersion() + 1;
    }

    private DocumentUploadResponse finalizeSynchronously(String docName, String title,
                                                          String originalFilename, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String tenantCode = tenantContext.getTenantCode();
        IngestOutcome outcome = ingestionService.ingest(
                new IngestRequest(tenantCode, docName, title, text, "upload"));
        documentFileService.saveFileMeta(outcome.documentId(),
                storagePathOf(tenantCode, docName, outcome.version()),
                fileTypeValidator.sanitizeFileName(originalFilename),
                fileTypeValidator.mimeTypeOf(originalFilename), bytes.length);
        log.info("Upload finalized: tenant={}, doc={}, status={}, version={}, size={}",
                tenantCode, docName, outcome.status(), outcome.version(), bytes.length);
        return toResponse(outcome, title, bytes.length);
    }

    private AsyncDocumentUploadResponse toAsyncResponse(Document document, DocumentIngestionJob job) {
        return new AsyncDocumentUploadResponse(document.getId(), document.getDocName(),
                job.getDocumentVersion(), job.getId(), job.getStatus(), job.getStage(),
                job.getCreatedAt(), "/api/v1/documents/" + document.getId()
                + "/ingestion-jobs/" + job.getId());
    }

    private Document documentIngestionDocument(String docName, String title, String fingerprint) {
        ingestionJobService.lockTenant(tenantContext.getTenantId());
        Document document = documentService.findByTenantAndName(tenantContext.getTenantId(), docName);
        if (document == null) {
            document = new Document();
            document.setTenantId(tenantContext.getTenantId());
            document.setDocName(docName);
            document.setTitle(title);
            document.setContentFingerprint(fingerprint);
            document.setStatus("INGESTING");
            document.setChunkCount(0);
            document.setVersion(1);
            document.setDeleted(false);
            documentService.save(document);
        }
        return document;
    }

    /** OpenDAL 键（research R7）：租户前缀隔离 + 版本化。 */
    public String storagePathOf(String tenantCode, String docName, int version) {
        return "document_file/" + tenantCode + "/" + docName + "/v" + version;
    }

    private void deleteObsoleteBlob(String oldPath, String tenantCode, String docName) {
        try {
            documentStorageOperator.delete(oldPath);
        } catch (Exception e) {
            // best-effort：删除失败仅 WARN 不阻断，下次重摄取兜底（tasks T024）
            log.warn("删除旧版本原文失败（暂不阻断）: tenant={}, doc={}, path={}", tenantCode, docName, oldPath);
        }
    }

    private DocumentUploadResponse toResponse(IngestOutcome outcome, String title, long sizeBytes) {
        List<DocumentUploadResponse.PipelineOutcome> pipelines = outcome.runs().stream()
                .map(r -> new DocumentUploadResponse.PipelineOutcome(
                        r.pipelineKey(), r.status().name(), r.chunkCount()))
                .toList();
        int chunkCount = outcome.runs().stream().mapToInt(r -> r.chunkCount()).sum();
        return new DocumentUploadResponse(outcome.docName(), title, outcome.status().name(),
                outcome.version(), chunkCount, sizeBytes, pipelines);
    }

    private void validateDocName(String docName) {
        if (docName == null || docName.isBlank()) {
            throw new ApiException("VALIDATION_FAILED", "docName 不能为空");
        }
        if (docName.length() > 255) {
            throw new ApiException("VALIDATION_FAILED", "docName 长度超过 255");
        }
    }
}

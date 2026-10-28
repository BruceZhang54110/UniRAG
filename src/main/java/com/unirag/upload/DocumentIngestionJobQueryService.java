package com.unirag.upload;

import com.unirag.infrastructure.persistence.entity.DocumentIngestionJob;
import com.unirag.infrastructure.persistence.service.DocumentIngestionJobService;
import com.unirag.upload.dto.DocumentIngestionJobStatusResponse;
import com.unirag.common.web.ApiException;
import org.springframework.stereotype.Service;

@Service
public class DocumentIngestionJobQueryService {

    private final DocumentIngestionJobService jobService;

    public DocumentIngestionJobQueryService(DocumentIngestionJobService jobService) {
        this.jobService = jobService;
    }

    public DocumentIngestionJobStatusResponse find(Long tenantId, Long documentId, Long jobId) {
        DocumentIngestionJob job = jobService.findByTenantDocumentAndJob(tenantId, documentId, jobId);
        String documentName = job == null ? null : jobService.findDocumentName(tenantId, documentId);
        if (job == null || documentName == null) {
            throw new ApiException("DOCUMENT_INGESTION_JOB_NOT_FOUND", "摄取任务不存在");
        }
        DocumentIngestionJobStatusResponse.Error error = job.getLastErrorCode() == null
                ? null : new DocumentIngestionJobStatusResponse.Error(
                job.getLastErrorCode(), safeMessage(job.getLastErrorCode()));
        return new DocumentIngestionJobStatusResponse(job.getId(), job.getDocumentId(), documentName,
                job.getDocumentVersion(), job.getStatus(), job.getStage(), job.getAttemptCount(),
                job.getMaxAttempts(), job.getNextAttemptAt(), job.getCreatedAt(), job.getUpdatedAt(),
                job.getFinishedAt(), error);
    }

    private String safeMessage(String code) {
        return switch (code) {
            case "TEMPORARY_INGESTION_FAILURE" -> "摄取服务暂时不可用，请等待自动重试";
            case "INGESTION_FAILED" -> "文档摄取失败，请检查文件后重试";
            default -> "文档摄取暂时未完成";
        };
    }
}

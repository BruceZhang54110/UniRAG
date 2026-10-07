package com.unirag.upload;

import com.unirag.upload.dto.DocumentIngestionJobStatusResponse;
import com.unirag.upload.support.ApiResponse;
import com.unirag.upload.support.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents/{documentId}/ingestion-jobs")
public class DocumentIngestionJobController {

    private final TenantContext tenantContext;
    private final DocumentIngestionJobQueryService queryService;

    public DocumentIngestionJobController(TenantContext tenantContext,
                                          DocumentIngestionJobQueryService queryService) {
        this.tenantContext = tenantContext;
        this.queryService = queryService;
    }

    @GetMapping("/{jobId}")
    public ApiResponse<DocumentIngestionJobStatusResponse> get(@PathVariable Long documentId,
                                                                @PathVariable Long jobId) {
        return ApiResponse.ok(queryService.find(tenantContext.getTenantId(), documentId, jobId));
    }
}

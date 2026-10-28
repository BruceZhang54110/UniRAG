package com.unirag.upload;

import com.unirag.ingestion.AsyncIngestionProperties;
import com.unirag.upload.dto.AsyncDocumentUploadResponse;
import com.unirag.upload.dto.DocumentUploadResponse;
import com.unirag.common.web.ApiResponse;
import com.unirag.common.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 一次性上传接口（contracts §2，小文件路径）：POST /api/v1/documents，multipart。
 * 租户经 {@link TenantContext} 定位（X-Tenant-Code，§1）。
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentUploadController {

    private final UploadService uploadService;
    private final AsyncIngestionProperties asyncProperties;

    public DocumentUploadController(UploadService uploadService, AsyncIngestionProperties asyncProperties) {
        this.uploadService = uploadService;
        this.asyncProperties = asyncProperties;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("docName") String docName,
            @RequestParam(value = "title", required = false) String title) throws IOException {
        if (asyncProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                    uploadService.finalizeUploadAsync(docName, title, file.getOriginalFilename(), file.getBytes())));
        }
        return ResponseEntity.ok(ApiResponse.ok(uploadService.uploadOneShot(
                docName, title, file.getOriginalFilename(), file.getBytes())));
    }
}

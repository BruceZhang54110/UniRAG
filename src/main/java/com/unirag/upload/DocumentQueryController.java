package com.unirag.upload;

import com.unirag.upload.dto.DocumentDetail;
import com.unirag.upload.dto.DocumentPage;
import com.unirag.common.web.ApiResponse;
import org.apache.opendal.Operator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 文档查询与下载（US2，contracts §4）：
 * GET /api/v1/documents（列表）、GET /api/v1/documents/{docName}（详情）、
 * GET /api/v1/documents/{docName}/file（原文下载，字节一致 + mime/归一化 filename 入 headers）。
 * 租户经 {@link com.unirag.common.tenant.TenantContext} 定位；下载本体为原始字节，不经 {data} 包装。
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentQueryController {

    private final DocumentQueryService queryService;
    private final Operator documentStorageOperator;

    public DocumentQueryController(DocumentQueryService queryService,
                                   Operator documentStorageOperator) {
        this.queryService = queryService;
        this.documentStorageOperator = documentStorageOperator;
    }

    /** 列表：仅本租户，按创建时间倒序（page 默认 1，size 默认 20）。 */
    @GetMapping
    public ApiResponse<DocumentPage> list(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queryService.list(page, size));
    }

    /** 详情：不存在（含跨租户）→ 404 DOCUMENT_NOT_FOUND。 */
    @GetMapping("/{docName}")
    public ApiResponse<DocumentDetail> detail(@PathVariable String docName) {
        return ApiResponse.ok(queryService.detail(docName));
    }

    /** 下载原文：按 storage_path 读 blob；Content-Type = 登记 mime，filename* 为 UTF-8 percent 编码（RFC 5987）。 */
    @GetMapping("/{docName}/file")
    public ResponseEntity<byte[]> download(@PathVariable String docName) {
        DocumentQueryService.DownloadTarget target = queryService.resolveDownload(docName);
        byte[] bytes = documentStorageOperator.read(target.storagePath());
        String encodedFileName = URLEncoder.encode(target.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(target.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFileName)
                .body(bytes);
    }

    private MediaType mediaTypeOf(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}

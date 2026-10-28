package com.unirag.upload;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unirag.infrastructure.persistence.entity.Document;
import com.unirag.infrastructure.persistence.mapper.DocumentMapper;
import com.unirag.infrastructure.persistence.service.DocumentService;
import com.unirag.upload.dto.DocumentDetail;
import com.unirag.upload.dto.DocumentInfoItem;
import com.unirag.upload.dto.DocumentPage;
import com.unirag.common.web.ApiException;
import com.unirag.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 文档查询（US2，contracts §4）：列表（本租户、创建时间倒序、分页）与详情（document JOIN document_file）。
 * 跨租户与不存在统一 404 DOCUMENT_NOT_FOUND（spec US2-2，不泄漏存在性）。
 */
@Service
public class DocumentQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final TenantContext tenantContext;
    private final DocumentService documentService;
    private final DocumentMapper documentMapper;

    public DocumentQueryService(TenantContext tenantContext,
                                DocumentService documentService,
                                DocumentMapper documentMapper) {
        this.tenantContext = tenantContext;
        this.documentService = documentService;
        this.documentMapper = documentMapper;
    }

    /** 列表：本租户未软删除文档，按创建时间倒序分页（total/items，contracts §4）。 */
    public DocumentPage list(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException("VALIDATION_FAILED",
                    "分页参数不合法：page 需 ≥ 1，size 需在 1-" + MAX_PAGE_SIZE + " 之间");
        }
        IPage<Map<String, Object>> result = documentMapper.pageUploadList(
                new Page<>(page, size), tenantContext.getTenantId());
        List<DocumentInfoItem> items = result.getRecords().stream().map(this::toInfoItem).toList();
        return new DocumentPage(result.getTotal(), items);
    }

    /** 详情：document JOIN document_file；不存在（含跨租户）→ 404 DOCUMENT_NOT_FOUND。 */
    public DocumentDetail detail(String docName) {
        Map<String, Object> row = documentMapper.findUploadDetailByTenantAndName(
                tenantContext.getTenantId(), docName);
        if (row == null) {
            throw notFound(docName);
        }
        return DocumentDetail.of(toInfoItem(row), epochMillis(row.get("updated_at")));
    }

    /**
     * 下载定位：按 docName 取原文存储信息（storagePath/登记 mime/fileName）。
     * 不存在（含跨租户）→ 404 DOCUMENT_NOT_FOUND。
     */
    public DownloadTarget resolveDownload(String docName) {
        Document document = documentService.findByTenantAndName(tenantContext.getTenantId(), docName);
        if (document == null) {
            throw notFound(docName);
        }
        Map<String, Object> row = documentMapper.findUploadDetailByTenantAndName(
                tenantContext.getTenantId(), docName);
        if (row == null || row.get("storage_path") == null) {
            throw new ApiException("DOCUMENT_NOT_FOUND", "文档原文不存在: " + docName);
        }
        return new DownloadTarget((String) row.get("storage_path"),
                (String) row.get("mime_type"), (String) row.get("file_name"));
    }

    private ApiException notFound(String docName) {
        return new ApiException("DOCUMENT_NOT_FOUND", "文档不存在: " + docName);
    }

    private DocumentInfoItem toInfoItem(Map<String, Object> row) {
        return new DocumentInfoItem(
                (String) row.get("doc_name"),
                (String) row.get("title"),
                (String) row.get("status"),
                ((Number) row.get("version")).intValue(),
                ((Number) row.get("chunk_count")).longValue(),
                row.get("size_bytes") == null ? null : ((Number) row.get("size_bytes")).longValue(),
                (String) row.get("mime_type"),
                epochMillis(row.get("created_at")));
    }

    private long epochMillis(Object value) {
        return value == null ? 0L : ((Date) value).getTime();
    }

    /** 原文下载定位结果（storage 读取所需三元组）。 */
    public record DownloadTarget(String storagePath, String mimeType, String fileName) {
    }
}

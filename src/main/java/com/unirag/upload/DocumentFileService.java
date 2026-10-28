package com.unirag.upload;

import com.unirag.infrastructure.persistence.entity.DocumentFile;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 原文文件元数据落库（document_file，data-model.md）。
 * 注意：本类与 MP 层 {@link com.unirag.infrastructure.persistence.service.DocumentFileService} 同名，
 * 构造器参数用全限定名避免遮蔽。
 */
@Service
public class DocumentFileService {

    private final com.unirag.infrastructure.persistence.service.DocumentFileService mpService;

    public DocumentFileService(
            com.unirag.infrastructure.persistence.service.DocumentFileService mpService) {
        this.mpService = mpService;
    }

    /**
     * 保存或刷新文档的原文元数据（document_id 主键，重复上传 = 覆盖 file meta）。
     */
    public void saveFileMeta(Long documentId, String storagePath, String fileName,
                             String mimeType, long sizeBytes) {
        Date now = new Date();
        DocumentFile existing = mpService.findByDocumentId(documentId);
        DocumentFile meta = existing != null ? existing : new DocumentFile();
        meta.setDocumentId(documentId);
        meta.setStoragePath(storagePath);
        meta.setFileName(fileName);
        meta.setMimeType(mimeType);
        meta.setSizeBytes(sizeBytes);
        if (existing == null) {
            meta.setCreatedAt(now);
        }
        meta.setUpdatedAt(now);
        mpService.saveOrUpdate(meta);
    }

    /** 按 document_id 查询（下载/查询用）。 */
    public DocumentFile findByDocumentId(Long documentId) {
        return mpService.findByDocumentId(documentId);
    }
}

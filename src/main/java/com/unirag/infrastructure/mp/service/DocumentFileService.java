package com.unirag.infrastructure.mp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.mp.entity.DocumentFile;

public interface DocumentFileService extends IService<DocumentFile> {

    /**
     * 按 document_id 查询原文元数据。
     */
    DocumentFile findByDocumentId(Long documentId);
}

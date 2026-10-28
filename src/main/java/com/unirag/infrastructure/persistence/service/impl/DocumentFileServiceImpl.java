package com.unirag.infrastructure.persistence.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.persistence.entity.DocumentFile;
import com.unirag.infrastructure.persistence.mapper.DocumentFileMapper;
import com.unirag.infrastructure.persistence.service.DocumentFileService;
import org.springframework.stereotype.Service;

@Service
public class DocumentFileServiceImpl
        extends ServiceImpl<DocumentFileMapper, DocumentFile>
        implements DocumentFileService {

    @Override
    public DocumentFile findByDocumentId(Long documentId) {
        return baseMapper.findByDocumentId(documentId);
    }
}

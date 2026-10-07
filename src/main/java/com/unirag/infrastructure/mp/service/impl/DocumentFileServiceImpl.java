package com.unirag.infrastructure.mp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.mp.entity.DocumentFile;
import com.unirag.infrastructure.mp.mapper.DocumentFileMapper;
import com.unirag.infrastructure.mp.service.DocumentFileService;
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

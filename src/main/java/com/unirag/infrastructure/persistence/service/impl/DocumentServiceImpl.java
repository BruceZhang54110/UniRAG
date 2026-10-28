package com.unirag.infrastructure.persistence.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.persistence.entity.Document;
import com.unirag.infrastructure.persistence.mapper.DocumentMapper;
import com.unirag.infrastructure.persistence.service.DocumentService;
import org.springframework.stereotype.Service;

@Service
public class DocumentServiceImpl
        extends ServiceImpl<DocumentMapper, Document>
        implements DocumentService {

    @Override
    public Document findByTenantAndName(Long tenantId, String docName) {
        return baseMapper.findByTenantAndName(tenantId, docName);
    }
}

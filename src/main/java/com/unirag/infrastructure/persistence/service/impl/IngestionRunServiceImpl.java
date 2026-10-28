package com.unirag.infrastructure.persistence.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.persistence.entity.IngestionRun;
import com.unirag.infrastructure.persistence.mapper.IngestionRunMapper;
import com.unirag.infrastructure.persistence.service.IngestionRunService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestionRunServiceImpl
        extends ServiceImpl<IngestionRunMapper, IngestionRun>
        implements IngestionRunService {

    @Override
    public List<IngestionRun> findByDocumentId(Long documentId) {
        return baseMapper.findByDocumentId(documentId);
    }

    @Override
    public List<IngestionRun> findUnfinishedByDocumentIds(List<Long> documentIds) {
        return baseMapper.findUnfinishedByDocumentIds(documentIds);
    }
}

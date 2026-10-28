package com.unirag.infrastructure.persistence.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.persistence.entity.DocumentChunk;
import com.unirag.infrastructure.persistence.mapper.DocumentChunkMapper;
import com.unirag.infrastructure.persistence.service.DocumentChunkService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentChunkServiceImpl
        extends ServiceImpl<DocumentChunkMapper, DocumentChunk>
        implements DocumentChunkService {

    @Override
    public List<DocumentChunk> findByDocumentAndPipeline(Long documentId, Long pipelineId) {
        return baseMapper.findByDocumentAndPipeline(documentId, pipelineId);
    }

    @Override
    public void deleteByDocumentAndPipeline(Long documentId, Long pipelineId) {
        baseMapper.deleteByDocumentAndPipeline(documentId, pipelineId);
    }
}

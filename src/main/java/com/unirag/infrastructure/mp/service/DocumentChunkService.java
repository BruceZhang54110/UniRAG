package com.unirag.infrastructure.mp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.mp.entity.DocumentChunk;

import java.util.List;

public interface DocumentChunkService extends IService<DocumentChunk> {

    /**
     * 查询某文档在某管线下的全部切片（按 seq_index 升序）。
     */
    List<DocumentChunk> findByDocumentAndPipeline(Long documentId, Long pipelineId);

    /**
     * 删除某文档在某管线下的全部切片行（"先清后写"清理，research.md R6）。
     */
    void deleteByDocumentAndPipeline(Long documentId, Long pipelineId);
}

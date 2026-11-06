package com.unirag.infrastructure.persistence.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.persistence.entity.DocumentChunk;

import java.util.List;

public interface DocumentChunkService extends IService<DocumentChunk> {

    /**
     * 查询某文档在某管线下的全部切片（按 seq_index 升序）。
     */
    List<DocumentChunk> findByDocumentAndPipeline(Long documentId, Long pipelineId);

    /**
     * 按子块命中批量取回父块（020 PARENT_FETCH 回填，contracts/parent-child-chunking.md §2）。
     *
     * <p>以子块 (document_id, pipeline_id, seq_index) 经 parent_chunk_id 自引用 join 取回
     * PARENT 行；返回键为子块 seq_index，值为父块行（无父块的子块不出现在结果中）。
     */
    java.util.Map<Integer, DocumentChunk> findParentsByChildSeqIndexes(Long documentId, Long pipelineId,
                                                                       List<Integer> childSeqIndexes);

    /**
     * 章节存在性判定（021 FR-007）：租户作用域内 section_path 等值或子节前缀；可选限定单文档。
     */
    boolean sectionExists(String tenantCode, Long documentId, String section);

    /**
     * 删除某文档在某管线下的全部切片行（"先清后写"清理，research.md R6）。
     */
    void deleteByDocumentAndPipeline(Long documentId, Long pipelineId);
}

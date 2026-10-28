package com.unirag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.persistence.entity.DocumentChunk;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @description 针对表【document_chunk】的数据库操作Mapper
 * @Entity com.unirag.infrastructure.persistence.entity.DocumentChunk
 */
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 查询某文档在某管线下的全部切片（按 seq_index 升序）。
     */
    List<DocumentChunk> findByDocumentAndPipeline(@Param("documentId") Long documentId,
                                                  @Param("pipelineId") Long pipelineId);

    /**
     * 删除某文档在某管线下的全部切片行，返回删除行数（"先清后写"清理，research.md R6）。
     */
    int deleteByDocumentAndPipeline(@Param("documentId") Long documentId,
                                    @Param("pipelineId") Long pipelineId);
}

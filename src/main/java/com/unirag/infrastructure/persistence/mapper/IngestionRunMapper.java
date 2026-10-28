package com.unirag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.persistence.entity.IngestionRun;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @description 针对表【ingestion_run】的数据库操作Mapper
 * @Entity com.unirag.infrastructure.persistence.entity.IngestionRun
 */
public interface IngestionRunMapper extends BaseMapper<IngestionRun> {

    /**
     * 查询某文档的全部管线执行记录（按 id 升序）。
     */
    List<IngestionRun> findByDocumentId(@Param("documentId") Long documentId);

    /**
     * 查询给定文档中未完结（finished_at 为空）的执行记录（启动恢复任务用，research.md R6）。
     */
    List<IngestionRun> findUnfinishedByDocumentIds(@Param("documentIds") List<Long> documentIds);
}

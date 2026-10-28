package com.unirag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.persistence.entity.IngestionPipeline;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @description 针对表【ingestion_pipeline】的数据库操作Mapper
 * @Entity com.unirag.infrastructure.persistence.entity.IngestionPipeline
 */
public interface IngestionPipelineMapper extends BaseMapper<IngestionPipeline> {

    /**
     * 查询租户下全部启用的摄取管线（未软删除，按 id 升序保证执行顺序确定）。
     */
    List<IngestionPipeline> findEnabledByTenant(@Param("tenantId") Long tenantId);
}

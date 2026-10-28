package com.unirag.infrastructure.persistence.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.persistence.entity.IngestionPipeline;

import java.util.List;

public interface IngestionPipelineService extends IService<IngestionPipeline> {

    /**
     * 查询租户下全部启用的摄取管线（未软删除，按 id 升序，contracts §2：摄取执行全部启用管线）。
     */
    List<IngestionPipeline> findEnabledByTenant(Long tenantId);
}

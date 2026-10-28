package com.unirag.infrastructure.persistence.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.persistence.entity.IngestionPipeline;
import com.unirag.infrastructure.persistence.mapper.IngestionPipelineMapper;
import com.unirag.infrastructure.persistence.service.IngestionPipelineService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestionPipelineServiceImpl
        extends ServiceImpl<IngestionPipelineMapper, IngestionPipeline>
        implements IngestionPipelineService {

    @Override
    public List<IngestionPipeline> findEnabledByTenant(Long tenantId) {
        return baseMapper.findEnabledByTenant(tenantId);
    }
}

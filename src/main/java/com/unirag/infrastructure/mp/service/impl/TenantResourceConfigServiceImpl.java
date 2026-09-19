package com.unirag.infrastructure.mp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.mp.entity.TenantResourceConfig;
import com.unirag.infrastructure.mp.mapper.TenantResourceConfigMapper;
import com.unirag.infrastructure.mp.service.TenantResourceConfigService;
import com.unirag.tenant.resource.ResourceType;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TenantResourceConfigServiceImpl
        extends ServiceImpl<TenantResourceConfigMapper, TenantResourceConfig>
        implements TenantResourceConfigService {

    @Override
    public List<com.unirag.tenant.resource.TenantResourceConfig> findAllEnabled() {
        return baseMapper.findAllEnabled().stream().map(this::toDomain).toList();
    }

    private com.unirag.tenant.resource.TenantResourceConfig toDomain(TenantResourceConfig entity) {
        return new com.unirag.tenant.resource.TenantResourceConfig(
                entity.getId(),
                entity.getTenantCode(),
                ResourceType.valueOf(entity.getResourceType()),
                entity.getResourceKey(),
                Boolean.TRUE.equals(entity.getPrimary()),
                entity.getConfig(),
                Boolean.TRUE.equals(entity.getEnabled()));
    }
}

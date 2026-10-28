package com.unirag.infrastructure.persistence.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.persistence.entity.TenantResourceConfig;
import com.unirag.infrastructure.persistence.mapper.TenantResourceConfigMapper;
import com.unirag.infrastructure.persistence.service.TenantResourceConfigService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TenantResourceConfigServiceImpl
        extends ServiceImpl<TenantResourceConfigMapper, TenantResourceConfig>
        implements TenantResourceConfigService {

    @Override
    public List<TenantResourceConfig> findAllEnabled() {
        return baseMapper.findAllEnabled();
    }
}

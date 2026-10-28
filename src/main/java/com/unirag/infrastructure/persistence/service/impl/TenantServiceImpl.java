package com.unirag.infrastructure.persistence.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.persistence.entity.Tenant;
import com.unirag.infrastructure.persistence.mapper.TenantMapper;
import com.unirag.infrastructure.persistence.service.TenantService;
import org.springframework.stereotype.Service;

/**
 * @author zhw
 */
@Service
public class TenantServiceImpl extends ServiceImpl<TenantMapper, Tenant> implements TenantService {

    @Override
    public Tenant findByCode(String tenantCode) {
        return baseMapper.findByCode(tenantCode);
    }
}

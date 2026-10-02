package com.unirag.infrastructure.mp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.mp.entity.Tenant;
import com.unirag.infrastructure.mp.mapper.TenantMapper;
import com.unirag.infrastructure.mp.service.TenantService;
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

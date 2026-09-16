package com.unirag.infrastructure.mp.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.unirag.infrastructure.mp.entity.TenantResourceConfig;
import com.unirag.infrastructure.mp.mapper.TenantResourceConfigMapper;
import com.unirag.infrastructure.mp.service.TenantResourceConfigService;
import org.springframework.stereotype.Service;

@Service
public class TenantResourceConfigServiceImpl extends ServiceImpl<TenantResourceConfigMapper, TenantResourceConfig> implements TenantResourceConfigService {
}

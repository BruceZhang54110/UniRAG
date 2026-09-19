package com.unirag.infrastructure.mp.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.mp.entity.TenantResourceConfig;

import java.util.List;

public interface TenantResourceConfigService extends IService<TenantResourceConfig> {

    /**
     * 查询所有启用租户下的启用资源配置，转换为领域对象返回
     * （research.md R6/R9：联表带出 tenant_code，按 id 升序）。
     */
    List<com.unirag.tenant.resource.TenantResourceConfig> findAllEnabled();
}

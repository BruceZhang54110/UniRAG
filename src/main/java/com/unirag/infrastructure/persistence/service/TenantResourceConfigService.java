package com.unirag.infrastructure.persistence.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.persistence.entity.TenantResourceConfig;

import java.util.List;

public interface TenantResourceConfigService extends IService<TenantResourceConfig> {

    /** 查询所有启用租户下的启用资源配置（联表带出 tenant_code，按 id 升序）。 */
    List<TenantResourceConfig> findAllEnabled();
}

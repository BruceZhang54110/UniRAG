package com.unirag.infrastructure.persistence.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.unirag.infrastructure.persistence.entity.Tenant;

public interface TenantService extends IService<Tenant> {

    /**
     * 按租户码查询未软删除租户；不存在返回 null（调用方决定失败语义）。
     */
    Tenant findByCode(String tenantCode);
}

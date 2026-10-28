package com.unirag.tenant.resource;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Value;

/**
 * 租户资源配置（领域对象）。
 *
 * <p>与持久化实体 {@code com.unirag.infrastructure.persistence.entity.TenantResourceConfig} 分离（research.md R9），
 * Registrar/Router/Provider 只依赖本对象；config 为 JSONB 配置体，由各 Provider 按
 * contracts/resource-config-schemas.md 自行解析。
 */
@Value
public class TenantResourceConfig {

    Long id;

    /** 租户对外编码（联表查询时从 tenant.tenant_code 带出）。 */
    String tenantCode;

    ResourceType resourceType;

    /** 租户内同类型唯一标识（原始值，未规范化）。 */
    String resourceKey;

    boolean primary;

    JsonNode config;

    boolean enabled;
}

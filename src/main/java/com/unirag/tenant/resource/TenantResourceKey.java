package com.unirag.tenant.resource;

/**
 * 租户资源寻址键：(tenantCode, resourceType, resourceKey)。
 */
public record TenantResourceKey(String tenantCode, ResourceType resourceType, String resourceKey) {

    public static TenantResourceKey of(String tenantCode, ResourceType resourceType, String resourceKey) {
        return new TenantResourceKey(tenantCode, resourceType, resourceKey);
    }
}

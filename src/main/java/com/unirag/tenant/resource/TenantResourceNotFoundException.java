package com.unirag.tenant.resource;

/**
 * 租户资源不存在时抛出的专用异常（Clarification Q2：不用 Optional/null，失败显式）。
 */
public class TenantResourceNotFoundException extends RuntimeException {

    public TenantResourceNotFoundException(String message) {
        super(message);
    }

    public static TenantResourceNotFoundException notFound(String tenantCode, ResourceType type, String resourceKey) {
        return new TenantResourceNotFoundException(
                "Tenant resource not found: tenant=" + tenantCode + ", type=" + type + ", key=" + resourceKey);
    }

    public static TenantResourceNotFoundException noPrimary(String tenantCode, ResourceType type, int instanceCount) {
        return new TenantResourceNotFoundException(
                "No primary tenant resource: tenant=" + tenantCode + ", type=" + type
                        + " (" + instanceCount + " instances registered)");
    }
}

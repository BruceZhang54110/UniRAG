package com.unirag.tenant.resource;

public interface TenantResourceProvider<T> {

    ResourceType resourceType();

    Class<T> beanType();

    T build(TenantResourceConfig config);

    default void validate(TenantResourceConfig config) { }
}

package com.unirag.tenant.resource;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 注册索引持有器：Registrar 在每次加载完成后发布新快照，
 * Router / HealthIndicator 通过本持有器读取最新索引。
 */
public class TenantResourceIndexRegistry {

    private final AtomicReference<TenantResourceIndex> current = new AtomicReference<>(TenantResourceIndex.empty());

    public void publish(TenantResourceIndex index) {
        current.set(index);
    }

    public TenantResourceIndex get() {
        return current.get();
    }
}

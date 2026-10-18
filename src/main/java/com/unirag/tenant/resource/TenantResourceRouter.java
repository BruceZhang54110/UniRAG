package com.unirag.tenant.resource;

import org.springframework.beans.BeansException;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 业务侧获取租户资源实例的统一入口（contracts/tenant-resource-router.md）。
 *
 * <p>语义契约（Clarification Q2/Q3）：
 * <ul>
 *   <li>{@link #get} 未命中抛 {@link TenantResourceNotFoundException}（message 含 tenant/type/key）；</li>
 *   <li>{@link #getPrimary} 命中 primary 返回之；无 primary 且仅一条实例时回退该实例；
 *       无 primary 且多条时抛 {@link TenantResourceNotFoundException}；重复 primary 已由
 *       Registrar 在加载期记 ERROR 并取 id 最小者；</li>
 *   <li>类型不符由容器原生检查抛出 {@link BeansException}。</li>
 * </ul>
 */
@Component
public class TenantResourceRouter {

    private final GenericApplicationContext applicationContext;
    private final TenantResourceIndexRegistry indexRegistry;

    public TenantResourceRouter(GenericApplicationContext applicationContext,
                                TenantResourceIndexRegistry indexRegistry) {
        this.applicationContext = applicationContext;
        this.indexRegistry = indexRegistry;
    }

    public <T> T get(String tenantCode, ResourceType type, String resourceKey, Class<T> beanType) {
        TenantResourceIndex index = indexRegistry.get();
        if (!index.contains(tenantCode, type, resourceKey)) {
            throw TenantResourceNotFoundException.notFound(tenantCode, type, resourceKey);
        }
        return applicationContext.getBean(index.beanName(tenantCode, type, resourceKey), beanType);
    }

    /** 只读索引：该租户是否已有可用的 primary 资源（不构建/不解析实例）。 */
    public boolean hasPrimary(String tenantCode, ResourceType type) {
        return indexRegistry.get().primaryKey(tenantCode, type).isPresent();
    }

    public <T> T getPrimary(String tenantCode, ResourceType type, Class<T> beanType) {
        TenantResourceIndex index = indexRegistry.get();
        TenantResourceKey key = index.primaryKey(tenantCode, type)
                .orElseGet(() -> {
                    int count = index.keysOf(tenantCode, type).size();
                    if (count == 1) {
                        return index.keysOf(tenantCode, type).get(0);
                    }
                    throw TenantResourceNotFoundException.noPrimary(tenantCode, type, count);
                });
        return applicationContext.getBean(index.beanName(key.tenantCode(), key.resourceType(), key.resourceKey()), beanType);
    }
}

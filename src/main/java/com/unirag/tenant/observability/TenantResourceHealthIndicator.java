package com.unirag.tenant.observability;

import com.unirag.tenant.resource.TenantResourceIndex;
import com.unirag.tenant.resource.TenantResourceIndexRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源基座健康指示器（FR-006/FR-009，research.md R10）：
 * details 暴露加载/失败明细；status 恒 UP——单条配置错误已被隔离并可见，
 * 不得拖垮全平台健康信号。
 *
 * <p>details 中绝不包含配置内容（FR-008），仅 tenantCode/type/key 与错误摘要。
 */
@Component
public class TenantResourceHealthIndicator implements HealthIndicator {

    private final TenantResourceIndexRegistry indexRegistry;

    public TenantResourceHealthIndicator(TenantResourceIndexRegistry indexRegistry) {
        this.indexRegistry = indexRegistry;
    }

    @Override
    public Health health() {
        TenantResourceIndex index = indexRegistry.get();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("loadedCount", index.loadedCount());
        details.put("failedCount", index.failedCount());
        List<Map<String, Object>> failures = index.failures().stream().map(f -> {
            Map<String, Object> failure = new LinkedHashMap<String, Object>();
            failure.put("tenantCode", f.tenantCode());
            failure.put("resourceType", f.resourceType().name());
            failure.put("resourceKey", f.resourceKey());
            failure.put("error", f.error());
            return failure;
        }).toList();
        details.put("failures", failures);
        return Health.up().withDetail("tenantResource", details).build();
    }
}

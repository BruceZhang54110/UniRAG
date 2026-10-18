package com.unirag.retrieval.rerank;

import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import org.springframework.stereotype.Component;

/**
 * 判断某个租户本次是否应启用重排：全局开关打开且租户配置了 primary 的 {@code RERANK_MODEL}。
 *
 * <p>只查资源索引，不构建模型；服务层用它决定是否扩大召回。
 */
@Component
public class RerankSupport {

    private final TenantResourceRouter router;
    private final RerankProperties properties;

    public RerankSupport(TenantResourceRouter router, RerankProperties properties) {
        this.router = router;
        this.properties = properties;
    }

    public boolean enabledFor(String tenantCode) {
        return properties.enabled() && router.hasPrimary(tenantCode, ResourceType.RERANK_MODEL);
    }

    public boolean enabledFor(StageContext ctx) {
        return enabledFor(ctx.tenantCode());
    }
}

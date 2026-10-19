package com.unirag.retrieval.query;

import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 判断某租户本次是否应启用查询改写：全局开关打开且配置了 {@code QUERY_REWRITE_MODEL}。
 *
 * <p>只查资源索引，不构建模型；资源本身默认复用租户主语言模型。
 */
@Component
public class QueryRewriteSupport {

    private final TenantResourceRouter router;
    private final QueryRewriteProperties properties;

    public QueryRewriteSupport(TenantResourceRouter router, QueryRewriteProperties properties) {
        this.router = router;
        this.properties = properties;
    }

    public boolean enabledFor(String tenantCode) {
        return properties.enabled() && router.hasPrimary(tenantCode, ResourceType.QUERY_REWRITE_MODEL);
    }

    public Optional<String> primaryKey(String tenantCode) {
        return router.primaryKey(tenantCode, ResourceType.QUERY_REWRITE_MODEL);
    }
}

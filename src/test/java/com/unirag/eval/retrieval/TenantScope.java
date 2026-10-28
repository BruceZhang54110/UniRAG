package com.unirag.eval.retrieval;

import com.unirag.common.tenant.TenantContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.function.Supplier;

/** 在请求作用域内初始化 {@link TenantContext}，供检索服务使用（复用 AbstractRetrievalIT 模式）。 */
public final class TenantScope {

    private TenantScope() {
    }

    public static <T> T inTenant(TenantContext tenantContext, String tenantCode, long tenantId,
                                 Supplier<T> action) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            tenantContext.initialize(tenantCode, tenantId);
            return action.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}

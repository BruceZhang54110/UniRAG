package com.unirag.common.tenant;

import com.unirag.common.web.ApiException;
import com.unirag.infrastructure.persistence.entity.Tenant;
import com.unirag.infrastructure.persistence.service.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * X-Tenant-Code 租户定位拦截器（contracts §1）：解析 → 校验租户存在 → 写入 {@link TenantContext}。
 */
@Slf4j
@Component
public class TenantContextInterceptor implements HandlerInterceptor {

    public static final String TENANT_HEADER = "X-Tenant-Code";

    private final TenantService tenantService;
    private final TenantContext tenantContext;

    public TenantContextInterceptor(TenantService tenantService, TenantContext tenantContext) {
        this.tenantService = tenantService;
        this.tenantContext = tenantContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenantCode = request.getHeader(TENANT_HEADER);
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new ApiException("TENANT_CODE_REQUIRED", "缺少租户标识请求头 X-Tenant-Code");
        }
        Tenant tenant = tenantService.findByCode(tenantCode);
        if (tenant == null) {
            throw new ApiException("TENANT_NOT_FOUND", "租户不存在: " + tenantCode);
        }
        tenantContext.initialize(tenant.getTenant_code(), tenant.getId());
        return true;
    }
}

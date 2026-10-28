package com.unirag.common.tenant;

import lombok.Getter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * 当前请求租户上下文（Web 租户定位基座，contracts §1/§7）。
 *
 * <p>由 {@link TenantContextInterceptor} 在请求入口解析 {@code X-Tenant-Code} 并校验租户存在，
 * 缺失 → 400 TENANT_CODE_REQUIRED，未知租户 → 404 TENANT_NOT_FOUND；
 * Controller/Service 经注入本 Bean 获取 tenantCode/tenantId，禁止直接读 header 重解析。
 * 跨 feature 约定：006 的检索/问答接口复用本组件（research R3）。
 */
@Getter
@Component
@RequestScope
public class TenantContext {

    private String tenantCode;
    private Long tenantId;

    public void initialize(String tenantCode, Long tenantId) {
        this.tenantCode = tenantCode;
        this.tenantId = tenantId;
    }
}

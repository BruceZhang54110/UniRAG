package com.unirag.common.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层注册：租户定位拦截器作用于 /api/**（contracts §1；Web 基座首次落地，006 复用）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TenantContextInterceptor tenantContextInterceptor;

    public WebConfig(TenantContextInterceptor tenantContextInterceptor) {
        this.tenantContextInterceptor = tenantContextInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantContextInterceptor).addPathPatterns("/api/**");
    }
}

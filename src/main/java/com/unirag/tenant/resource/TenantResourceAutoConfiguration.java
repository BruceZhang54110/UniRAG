package com.unirag.tenant.resource;

import com.unirag.infrastructure.mp.service.TenantResourceConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;

/**
 * 多租户资源基座装配（research.md R1）。Provider 实现以 @Component 存在于
 * com.unirag.tenant.resource.provider 包，由组件扫描自动收集为 List 注入。
 */
@Configuration
public class TenantResourceAutoConfiguration {

    @Bean
    public TenantResourceIndexRegistry tenantResourceIndexRegistry() {
        return new TenantResourceIndexRegistry();
    }

    @Bean
    public TenantResourceRegistrar tenantResourceRegistrar(
            TenantResourceConfigService tenantResourceConfigService,
            GenericApplicationContext applicationContext,
            List<TenantResourceProvider<?>> providers,
            TenantResourceIndexRegistry indexRegistry) {
        return new TenantResourceRegistrar(tenantResourceConfigService, applicationContext, providers, indexRegistry);
    }
}

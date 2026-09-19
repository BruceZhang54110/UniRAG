package com.unirag.tenant.resource;

import com.unirag.infrastructure.mp.service.TenantResourceConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;

/**
 * 启动期租户资源注册器：读取全部启用租户的资源配置，经 Provider SPI 创建实例并注册为
 * 命名 Bean（research.md R1/R4/R6/R14）。
 *
 * <p>核心逻辑在 {@link #loadAndRegister()}，可重复调用（幂等：已注册的 Bean 跳过并保留索引）。
 * 单条配置失败仅记录并跳过（FR-006），成功才注册（FR-010 不部分注册）；
 * 日志只含 tenantCode/type/key/原因，不含配置内容（FR-008）。
 */
@Slf4j
public class TenantResourceRegistrar implements ApplicationRunner {

    private final TenantResourceConfigService tenantResourceConfigService;
    private final GenericApplicationContext applicationContext;
    private final List<TenantResourceProvider<?>> providers;
    private final TenantResourceIndexRegistry indexRegistry;

    public TenantResourceRegistrar(TenantResourceConfigService tenantResourceConfigService,
                                   GenericApplicationContext applicationContext,
                                   List<TenantResourceProvider<?>> providers,
                                   TenantResourceIndexRegistry indexRegistry) {
        this.tenantResourceConfigService = tenantResourceConfigService;
        this.applicationContext = applicationContext;
        this.providers = providers;
        this.indexRegistry = indexRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        loadAndRegister();
    }

    /**
     * 加载并注册
     */
    public void loadAndRegister() {
        List<TenantResourceConfig> configs = tenantResourceConfigService.findAllEnabled();
        log.info("Loading tenant resources, {} enabled config(s) found", configs.size());
        TenantResourceIndex.Builder index = TenantResourceIndex.builder();

        for (TenantResourceConfig config : configs) {
            ResourceType type = config.getResourceType();
            TenantResourceProvider<?> provider = findProvider(type);
            if (provider == null) {
                log.warn("No provider for tenant resource type {} (tenant={}, key={}), skipped",
                        type, config.getTenantCode(), config.getResourceKey());
                continue;
            }
            String beanName = TenantResourceIndex.beanNameOf(type, config.getTenantCode(), config.getResourceKey());
            if (applicationContext.containsBeanDefinition(beanName)) {
                // 幂等重载：Bean 已存在则只保留索引，不重复注册（research.md R14）
                log.debug("Tenant resource bean {} already registered, skipped", beanName);
                index.registered(config, beanName);
                continue;
            }
            try {
                // 先同步构建成功，再注册——保证 FR-010：一条记录要么完整创建成功、要么完全不创建
                buildAndRegister(provider, beanName, config);
                index.registered(config, beanName);
                log.info("Registered tenant resource: {}", beanName);
            } catch (Exception e) {
                String reason = rootCauseMessage(e);
                index.failed(config, reason);
                log.error("Failed to create tenant resource: tenant={}, type={}, key={}, reason={}",
                        config.getTenantCode(), type, config.getResourceKey(), reason);
            }
        }

        indexRegistry.publish(index.build());
        log.info("Tenant resource loading finished: {} loaded, {} failed",
                indexRegistry.get().loadedCount(), indexRegistry.get().failedCount());
    }

    private TenantResourceProvider<?> findProvider(ResourceType type) {
        return providers.stream().filter(p -> p.resourceType() == type).findFirst().orElse(null);
    }

    private <T> void buildAndRegister(TenantResourceProvider<T> provider, String beanName, TenantResourceConfig config) {
        T bean = provider.build(config);
        applicationContext.registerBean(beanName, provider.beanType(), () -> bean);
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}

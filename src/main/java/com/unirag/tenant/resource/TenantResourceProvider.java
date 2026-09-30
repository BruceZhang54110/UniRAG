package com.unirag.tenant.resource;

import java.util.Optional;

public interface TenantResourceProvider<T> {

    ResourceType resourceType();

    Class<T> beanType();

    T build(TenantResourceConfig config);

    default void validate(TenantResourceConfig config) { }

    /**
     * 冲突键（research.md R4）：冲突语义类型相关、去重机制类型无关——返回本次加载内
     * 标识"同一物理资源"的键；单次 {@link TenantResourceRegistrar#loadAndRegister()} 中
     * 冲突键相同的配置仅先加载者（记录 id 升序）可构建，后加载者记 ERROR 并故障隔离。
     *
     * <p>默认 {@code Optional.empty()} = 该类型不参与跨配置去重；配置字段不足以构成键时
     * 亦返回 empty（构建阶段仍按必填校验失败）。
     */
    default Optional<String> collisionKey(TenantResourceConfig config) {
        return Optional.empty();
    }
}

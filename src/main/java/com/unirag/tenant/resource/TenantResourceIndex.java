package com.unirag.tenant.resource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租户资源注册索引（不可变）。由 {@link TenantResourceRegistrar} 在每次加载后构建并发布，
 * {@link TenantResourceRouter} 与 HealthIndicator 只读使用（research.md R5）。
 *
 * <p>Bean 命名规则（research.md R2 / Clarification Q1）：{@code {beanPrefix}_{tenantCode}_{resourceKey}}，
 * 非法字符（非 [a-zA-Z0-9_-]）替换为 "_" 以规避命名冲突。
 */
public final class TenantResourceIndex {

    /** (tenantCode, resourceType) 主键。 */
    public record TenantResourceTypeKey(String tenantCode, ResourceType resourceType) {
    }

    /** 加载失败的资源明细（健康检查/details 用，绝不包含敏感配置内容）。 */
    public record Failure(String tenantCode, ResourceType resourceType, String resourceKey, String error) {
    }

    private static final TenantResourceIndex EMPTY = new TenantResourceIndex(
            Map.of(), Map.of(), Set.of(), 0, 0, List.of());

    private final Map<TenantResourceKey, String> beanNames;
    /** (tenantCode, type) -> (配置 id -> key)，按 id 升序；重复 primary 防御取 firstEntry。 */
    private final Map<TenantResourceTypeKey, NavigableMap<Long, TenantResourceKey>> primaries;
    private final Set<TenantResourceTypeKey> registeredPairs;
    private final int loadedCount;
    private final int failedCount;
    private final List<Failure> failures;

    public TenantResourceIndex(Map<TenantResourceKey, String> beanNames,
                               Map<TenantResourceTypeKey, NavigableMap<Long, TenantResourceKey>> primaries,
                               Set<TenantResourceTypeKey> registeredPairs,
                               int loadedCount, int failedCount, List<Failure> failures) {
        this.beanNames = Collections.unmodifiableMap(beanNames);
        this.primaries = Collections.unmodifiableMap(primaries);
        this.registeredPairs = Collections.unmodifiableSet(registeredPairs);
        this.loadedCount = loadedCount;
        this.failedCount = failedCount;
        this.failures = List.copyOf(failures);
    }

    public static TenantResourceIndex empty() {
        return EMPTY;
    }

    /**
     * 生成beanName
     * @param type
     * @param tenantCode
     * @param resourceKey
     * @return
     */
    public static String beanNameOf(ResourceType type, String tenantCode, String resourceKey) {
        return type.beanPrefix() + "_" + normalize(tenantCode) + "_" + normalize(resourceKey);
    }

    static String normalize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public boolean contains(String tenantCode, ResourceType type, String resourceKey) {
        return beanNames.containsKey(TenantResourceKey.of(tenantCode, type, resourceKey));
    }

    /**
     * 获取beanName
     * @param tenantCode
     * @param type
     * @param resourceKey
     * @return
     */
    public String beanName(String tenantCode, ResourceType type, String resourceKey) {
        return beanNames.get(TenantResourceKey.of(tenantCode, type, resourceKey));
    }

    /** 该 (tenant, type) 的主资源 key；重复 primary（防御路径）取配置 id 最小者。 */
    public Optional<TenantResourceKey> primaryKey(String tenantCode, ResourceType type) {
        NavigableMap<Long, TenantResourceKey> byId = primaries.get(new TenantResourceTypeKey(tenantCode, type));
        if (byId == null || byId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.firstEntry()).map(Map.Entry::getValue);
    }

    /** 该 (tenant, type) 下全部已注册实例的 key（按配置 id 升序）。 */
    public List<TenantResourceKey> keysOf(String tenantCode, ResourceType type) {
        NavigableMap<Long, TenantResourceKey> byId = primaries.get(new TenantResourceTypeKey(tenantCode, type));
        if (byId == null || byId.isEmpty()) {
            // 无 primary 时 primaries 无该 pair 条目，从 beanNames 兜底过滤
            TenantResourceTypeKey pair = new TenantResourceTypeKey(tenantCode, type);
            if (!registeredPairs.contains(pair)) {
                return List.of();
            }
            return beanNames.keySet().stream()
                    .filter(k -> k.tenantCode().equals(tenantCode) && k.resourceType() == type)
                    .collect(Collectors.toList());
        }
        return List.copyOf(byId.values());
    }

    public int loadedCount() {
        return loadedCount;
    }

    public int failedCount() {
        return failedCount;
    }

    public List<Failure> failures() {
        return failures;
    }

    /** 可变建造器，Registrar 每次加载后重建索引。 */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private static final Logger log = LoggerFactory.getLogger(TenantResourceIndex.class);

        private final Map<TenantResourceKey, String> beanNames = new java.util.LinkedHashMap<>();
        private final Map<TenantResourceTypeKey, NavigableMap<Long, TenantResourceKey>> primaries = new java.util.HashMap<>();
        private final Set<TenantResourceTypeKey> registeredPairs = new java.util.HashSet<>();
        private final List<Failure> failures = new java.util.ArrayList<>();
        private int loadedCount;
        private int failedCount;

        private Builder() {
        }

        public Builder registered(TenantResourceConfig config, String beanName) {
            TenantResourceKey key = TenantResourceKey.of(config.getTenantCode(), config.getResourceType(), config.getResourceKey());
            beanNames.put(key, beanName);
            TenantResourceTypeKey pair = new TenantResourceTypeKey(config.getTenantCode(), config.getResourceType());
            registeredPairs.add(pair);
            if (config.isPrimary()) {
                NavigableMap<Long, TenantResourceKey> byId =
                        primaries.computeIfAbsent(pair, k -> new TreeMap<>());
                if (!byId.isEmpty() && !byId.containsKey(config.getId())) {
                    // DB 部分唯一索引之外的防御路径（FR-011）：重复 primary 记 ERROR，取配置 id 最小者
                    log.error("Duplicate primary tenant resource for tenant={}, type={} (keeping id={})",
                            config.getTenantCode(), config.getResourceType(), byId.firstKey());
                }
                byId.putIfAbsent(config.getId(), key); // 查询按 id 升序，putIfAbsent 保留最小 id
            }
            loadedCount++;
            return this;
        }

        public Builder failed(TenantResourceConfig config, String error) {
            failures.add(new Failure(config.getTenantCode(), config.getResourceType(), config.getResourceKey(), error));
            failedCount++;
            return this;
        }

        public TenantResourceIndex build() {
            return new TenantResourceIndex(beanNames, primaries, registeredPairs, loadedCount, failedCount, failures);
        }
    }
}

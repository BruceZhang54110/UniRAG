package com.unirag.infrastructure.persistence;

import com.unirag.AbstractPostgresIntegrationTest;
import com.unirag.infrastructure.persistence.service.TenantResourceConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findAllEnabled 联表查询 + JSONB 映射冒烟用例（tasks.md T008 / research.md R6/R7）。
 * 真实连接本地 PG；租户码全局唯一（smokeQ1）。
 */
class TenantResourceConfigServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    TenantResourceConfigService service;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void findAllEnabledReturnsOnlyEnabledConfigsOfEnabledTenantsOrderedById() {
        long tenantOn = insertTenant("smokeQ1_on");
        long tenantOff = insertTenant("smokeQ1_off");
        disableTenant(tenantOff);

        insertConfig(tenantOn, "LLM", "default", true, """
                {"base-url":"https://api.example.com/v1","api-key":"sk-smokeQ1","model-name":"smoke-model-a"}""");
        insertConfig(tenantOn, "EMBEDDING_MODEL", "small", false, """
                {"base-url":"https://api.example.com/v1","api-key":"sk-smokeQ1","model-name":"smoke-model-b"}""");
        insertConfig(tenantOn, "LLM", "disabledCfg", false, """
                {"base-url":"https://api.example.com/v1","api-key":"sk-smokeQ1","model-name":"smoke-model-c"}""");
        disableConfig("smokeQ1_on", "disabledCfg");
        insertConfig(tenantOff, "LLM", "offTenant", false, """
                {"base-url":"https://api.example.com/v1","api-key":"sk-smokeQ1","model-name":"smoke-model-d"}""");

        List<com.unirag.infrastructure.persistence.entity.TenantResourceConfig> all = service.findAllEnabled();
        List<com.unirag.infrastructure.persistence.entity.TenantResourceConfig> mine = all.stream()
                .filter(c -> c.getTenantCode().startsWith("smokeQ1")).toList();

        assertThat(mine).hasSize(2);
        // id 升序：default 先于 small
        assertThat(mine.get(0).getResourceKey()).isEqualTo("default");
        assertThat(mine.get(1).getResourceKey()).isEqualTo("small");
        assertThat(mine.get(0).getTenantCode()).isEqualTo("smokeQ1_on");
        assertThat(mine.get(0).getResourceType()).isEqualTo("LLM");
        assertThat(mine.get(0).getPrimary()).isTrue();
        // JSONB -> JsonNode 映射
        assertThat(mine.get(0).getConfig().get("model-name").asText()).isEqualTo("smoke-model-a");
        assertThat(mine.stream().map(com.unirag.infrastructure.persistence.entity.TenantResourceConfig::getResourceKey))
                .doesNotContain("disabledCfg", "offTenant");
    }

    private long insertTenant(String code) {
        // 开发库中测试数据持久存在（research.md R12），upsert 保证用例可重复运行
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO tenant (tenant_code, name) VALUES (?, ?)
                ON CONFLICT (tenant_code) DO UPDATE SET name = EXCLUDED.name,
                    enabled = TRUE, deleted = FALSE
                RETURNING id""",
                Long.class, code, code);
    }

    private void disableTenant(long tenantId) {
        jdbcTemplate.update("UPDATE tenant SET enabled = FALSE WHERE id = ?", tenantId);
    }

    private void disableConfig(String tenantCode, String resourceKey) {
        jdbcTemplate.update("""
                UPDATE tenant_resource_config c SET enabled = FALSE
                FROM tenant t WHERE c.tenant_id = t.id
                AND t.tenant_code = ? AND c.resource_key = ?""", tenantCode, resourceKey);
    }

    private void insertConfig(long tenantId, String type, String key, boolean primary, String json) {
        jdbcTemplate.update("""
                INSERT INTO tenant_resource_config (tenant_id, resource_type, resource_key, is_primary, config)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (tenant_id, resource_type, resource_key) DO UPDATE
                SET is_primary = EXCLUDED.is_primary, config = EXCLUDED.config,
                    enabled = TRUE, deleted = FALSE""", tenantId, type, key, primary, json);
    }
}

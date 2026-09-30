package com.unirag.tenant.resource;

import com.unirag.AbstractPostgresIntegrationTest;
import com.unirag.tenant.observability.TenantResourceHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US2 验收（spec.md FR-006/FR-007/FR-008）：向量资源加载失败出现在 health details 的
 * failures 明细中（含 tenantCode/type/resourceKey/reason），且不含密码。
 * 租户码全局唯一（US4 后缀），nocreateUS4 复用 T007 场景约定。
 */
class EmbeddingStoreHealthIT extends AbstractPostgresIntegrationTest {

    @Autowired
    TenantResourceRegistrar registrar;

    @Autowired
    TenantResourceHealthIndicator healthIndicator;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Environment environment;

    @Test
    void healthDetailsExposeEmbeddingStoreFailureWithoutPassword() {
        long health = insertTenant("healthUS4");
        long nocreate = insertTenant("nocreateUS4");
        insertConfig(health, "EMBEDDING_STORE", "default", true, storeConfig("embeddings_healthUS4", true));
        insertConfig(nocreate, "EMBEDDING_STORE", "default", true,
                storeConfig("embeddings_nocreateUS4", false));

        registrar.loadAndRegister();

        assertThat(applicationContext.containsBean("embeddingStore_healthUS4_default")).isTrue();
        assertThat(applicationContext.containsBean("embeddingStore_nocreateUS4_default")).isFalse();

        Map<String, Object> details = healthIndicator.health().getDetails();
        @SuppressWarnings("unchecked")
        Map<String, Object> tenantResource = (Map<String, Object>) details.get("tenantResource");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) tenantResource.get("failures");
        assertThat(failures).anySatisfy(failure -> {
            assertThat(failure)
                    .containsEntry("tenantCode", "nocreateUS4")
                    .containsEntry("resourceType", "EMBEDDING_STORE")
                    .containsEntry("resourceKey", "default");
            assertThat(failure.get("error").toString()).contains("表不存在");
        });

        // FR-008：密码值不出现在 health details 的序列化结果中
        assertThat(details.toString()).doesNotContain(pgPassword());
    }

    private String storeConfig(String table, boolean createTable) {
        return """
                {"host":"localhost","port":5432,"database":"uni_rag_db","user":"uni_rag_user",
                 "password":"%s","table":"%s","dimension":1024,"create-table":%b}"""
                .formatted(pgPassword(), table, createTable);
    }

    private String pgPassword() {
        return environment.getProperty("spring.datasource.password");
    }

    private long insertTenant(String code) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO tenant (tenant_code, name) VALUES (?, ?)
                ON CONFLICT (tenant_code) DO UPDATE SET name = EXCLUDED.name,
                    enabled = TRUE, deleted = FALSE
                RETURNING id""",
                Long.class, code, code);
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

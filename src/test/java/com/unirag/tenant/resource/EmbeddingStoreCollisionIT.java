package com.unirag.tenant.resource;

import com.unirag.AbstractPostgresIntegrationTest;
import com.unirag.tenant.observability.TenantResourceHealthIndicator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 验收（spec.md FR-004/FR-008，contracts G2）：跨租户同名同库表冲突加载期检出、
 * 先加载者（id 升序）胜；非法表名构建失败且日志不回显配置原文（含密码）。
 * 租户码全局唯一（US4 后缀）。
 */
@ExtendWith(OutputCaptureExtension.class)
class EmbeddingStoreCollisionIT extends AbstractPostgresIntegrationTest {

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
    void sameTableAcrossTenantsIsDetectedAndIsolated(CapturedOutput output) {
        String marker = "pw-colUS4-SECRETMARKER";
        long colA = insertTenant("colAUS4");
        long colB = insertTenant("colBUS4");
        insertConfig(colA, "EMBEDDING_STORE", "first", true,
                storeConfig("embeddings_collision_us4", marker));
        insertConfig(colB, "EMBEDDING_STORE", "second", true,
                storeConfig("embeddings_collision_us4", marker));

        registrar.loadAndRegister();

        // R4：加载顺序 = 配置 id 升序，先加载者胜
        assertThat(applicationContext.containsBean("embeddingStore_colAUS4_first")).isTrue();
        assertThat(applicationContext.containsBean("embeddingStore_colBUS4_second")).isFalse();

        // ERROR 日志含双方租户 code 与表名（SC-003 可定位），不含密码（FR-008）
        String logs = output.getOut() + output.getErr();
        assertThat(logs).contains("colAUS4", "colBUS4", "embeddings_collision_us4");
        assertThat(logs).doesNotContain(marker);

        // 失败明细进入 health details（验收场景 1）
        Map<String, Object> details = healthIndicator.health().getDetails();
        @SuppressWarnings("unchecked")
        Map<String, Object> tenantResource = (Map<String, Object>) details.get("tenantResource");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) tenantResource.get("failures");
        assertThat(failures).anySatisfy(failure -> assertThat(failure)
                .containsEntry("tenantCode", "colBUS4")
                .containsEntry("resourceType", "EMBEDDING_STORE")
                .containsEntry("resourceKey", "second"));
        assertThat(details.toString()).doesNotContain(marker);
    }

    @Test
    void invalidTableNameFailsWithoutLeakingConfig(CapturedOutput output) {
        String marker = "pw-invalUS4-SECRETMARKER";
        long inval = insertTenant("invalUS4");
        insertConfig(inval, "EMBEDDING_STORE", "default", true, """
                {"host":"localhost","port":5432,"database":"uni_rag_db","user":"uni_rag_user",
                 "password":"%s","table":"embeddings-bad","dimension":1024}""".formatted(marker));

        registrar.loadAndRegister();

        assertThat(applicationContext.containsBean("embeddingStore_invalUS4_default")).isFalse();

        // R3：日志含非法表名但不回显配置原文（FR-008 密码红线）
        String logs = output.getOut() + output.getErr();
        assertThat(logs).contains("embeddings-bad");
        assertThat(logs).doesNotContain(marker);
    }

    private String storeConfig(String table, String password) {
        return """
                {"host":"localhost","port":5432,"database":"uni_rag_db","user":"uni_rag_user",
                 "password":"%s","table":"%s","dimension":1024}""".formatted(password, table);
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

package com.unirag.tenant.resource;

import com.unirag.AbstractPostgresIntegrationTest;
import com.unirag.tenant.observability.TenantResourceHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 验收（spec.md FR-005，contracts G3，SC-005）：已存在向量表维度与配置不一致时
 * 加载期构建失败并隔离，失败原因含实际维度与配置维度；合法资源不受影响。
 * 租户码全局唯一（US4 后缀）。
 *
 * <p>类级数据清理：dimUS4 的"配置 1024 / 表 768"不一致状态必须在上下文启动加载期即成立，
 * 否则启动期注册的历史 Bean 会污染断言；测试结束后删除本类配置，避免残留影响后续运行。
 */
@Sql(executionPhase = Sql.ExecutionPhase.AFTER_TEST_CLASS, statements = {
        "DELETE FROM tenant_resource_config WHERE tenant_id IN"
                + " (SELECT id FROM tenant WHERE tenant_code IN ('dimUS4', 'goodUS4'))",
        "DELETE FROM tenant WHERE tenant_code IN ('dimUS4', 'goodUS4')"
})
class EmbeddingStoreDimensionMismatchIT extends AbstractPostgresIntegrationTest {

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
    void dimensionMismatchIsDetectedAtLoadTimeAndIsolated() {
        // 预建 vector(768) 表，配置 dimension=1024；DROP 保证维度断言不被历史运行残留污染
        jdbcTemplate.execute("DROP TABLE IF EXISTS embeddings_dimUS4");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS embeddings_dimUS4 (
                    embedding_id UUID PRIMARY KEY,
                    embedding vector(768),
                    text TEXT NULL,
                    metadata JSON NULL)""");

        long dim = insertTenant("dimUS4");
        long good = insertTenant("goodUS4");
        insertConfig(dim, "EMBEDDING_STORE", "default", true, storeConfig("embeddings_dimUS4", 1024));
        insertConfig(good, "EMBEDDING_STORE", "default", true, storeConfig("embeddings_goodUS4", 1024));

        registrar.loadAndRegister();

        // R2：维度不匹配在加载期暴露，该条不注册（FR-010 不产生半就绪 Bean）
        assertThat(applicationContext.containsBean("embeddingStore_dimUS4_default")).isFalse();
        // FR-006：故障隔离不影响其他租户
        assertThat(applicationContext.containsBean("embeddingStore_goodUS4_default")).isTrue();

        // SC-005：失败原因含实际维度 768 与配置维度 1024，1 分钟可定位
        Map<String, Object> details = healthIndicator.health().getDetails();
        @SuppressWarnings("unchecked")
        Map<String, Object> tenantResource = (Map<String, Object>) details.get("tenantResource");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) tenantResource.get("failures");
        assertThat(failures).anySatisfy(failure -> {
            assertThat(failure)
                    .containsEntry("tenantCode", "dimUS4")
                    .containsEntry("resourceType", "EMBEDDING_STORE")
                    .containsEntry("resourceKey", "default");
            assertThat(failure.get("error").toString()).contains("768", "1024");
        });
    }

    private String storeConfig(String table, int dimension) {
        return """
                {"host":"localhost","port":5432,"database":"uni_rag_db","user":"uni_rag_user",
                 "password":"%s","table":"%s","dimension":%d}"""
                .formatted(pgPassword(), table, dimension);
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

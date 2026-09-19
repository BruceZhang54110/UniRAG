package com.unirag.tenant.resource;

import com.unirag.AbstractPostgresIntegrationTest;
import com.unirag.tenant.observability.TenantResourceHealthIndicator;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3：单条配置失败不影响其他租户（FR-006/FR-010）、日志不含敏感信息（FR-008）、
 * 健康检查暴露失败明细（FR-009/SC-003）。租户码全局唯一（US3 后缀）。
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantResourceRegistrarFailureIT extends AbstractPostgresIntegrationTest {

    private static final String SECRET_MARKER = "sk-goodUS3-SECRETMARKER";

    @Autowired
    TenantResourceRegistrar registrar;

    @Autowired
    TenantResourceHealthIndicator healthIndicator;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void singleFailureIsIsolatedAndObservable(CapturedOutput output) {
        long bad = insertTenant("badUS3");
        long good = insertTenant("goodUS3");
        insertConfig(bad, "LLM", "default", true, """
                {"base-url":"https://api.bad.example.com/v1","api-key":"sk-badUS3","model-name":"gpt-bad-us3"}""");
        insertConfig(bad, "EMBEDDING_MODEL", "broken", true, """
                {"base-url":"https://api.broken.example.com/v1","model-name":"embed-broken-us3"}""");
        insertConfig(good, "LLM", "default", true, """
                {"base-url":"https://api.good.example.com/v1","api-key":"%s","model-name":"gpt-good-us3"}""".formatted(SECRET_MARKER));

        registrar.loadAndRegister();

        // 合法资源全部可用；失败资源完全不注册（FR-010 不部分注册）
        assertThat(applicationContext.containsBean("llm_badUS3_default")).isTrue();
        assertThat(applicationContext.containsBean("llm_goodUS3_default")).isTrue();
        assertThat(applicationContext.containsBean("embeddingModel_badUS3_broken")).isFalse();

        // ERROR 日志含定位信息（SC-003：1 分钟内可定位）
        String logs = output.getOut() + output.getErr();
        assertThat(logs).contains("badUS3", "EMBEDDING_MODEL", "broken", "api-key");

        // FR-008：任何日志不得含 api-key 值
        assertThat(logs).doesNotContain(SECRET_MARKER, "sk-badUS3");

        // 健康检查明细（FR-009）。索引是全局的（开发库累积历史测试数据），只断言本类相关明细
        Map<String, Object> details = healthIndicator.health().getDetails();
        @SuppressWarnings("unchecked")
        Map<String, Object> tenantResource = (Map<String, Object>) details.get("tenantResource");
        assertThat((int) tenantResource.get("loadedCount")).isGreaterThanOrEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) tenantResource.get("failures");
        assertThat((int) tenantResource.get("failedCount")).isEqualTo(failures.size());
        assertThat(failures).anySatisfy(failure -> assertThat(failure)
                .containsEntry("tenantCode", "badUS3")
                .containsEntry("resourceType", "EMBEDDING_MODEL")
                .containsEntry("resourceKey", "broken"));
        assertThat(failures.stream().map(f -> f.get("error").toString()).toList())
                .noneMatch(error -> error.contains(SECRET_MARKER));

        // 失败不影响已注册资源的正常使用
        OpenAiChatModel goodLlm = applicationContext.getBean("llm_goodUS3_default", OpenAiChatModel.class);
        assertThat(goodLlm.defaultRequestParameters().modelName()).isEqualTo("gpt-good-us3");
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

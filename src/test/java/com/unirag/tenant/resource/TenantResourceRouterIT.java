package com.unirag.tenant.resource;

import com.unirag.AbstractPostgresIntegrationTest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US2 契约测试（contracts/tenant-resource-router.md）：get / getPrimary 全语义表。
 * 租户码全局唯一（US2 后缀）。
 */
class TenantResourceRouterIT extends AbstractPostgresIntegrationTest {

    @Autowired
    TenantResourceRegistrar registrar;

    @Autowired
    TenantResourceRouter router;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void getReturnsTenantOwnInstance() {
        seed();
        registrar.loadAndRegister();

        OpenAiChatModel acme = router.get("acmeUS2", ResourceType.LLM, "default", OpenAiChatModel.class);
        OpenAiChatModel globex = router.get("globexUS2", ResourceType.LLM, "default", OpenAiChatModel.class);

        // 跨租户同 key 返回不同实例（SC-002），且各自参数生效
        assertThat(acme).isNotSameAs(globex);
        assertThat(acme.defaultRequestParameters().modelName()).isEqualTo("gpt-acme-us2");
        assertThat(globex.defaultRequestParameters().modelName()).isEqualTo("gpt-globex-us2");
        // 同一调用返回同一单例
        assertThat(router.get("acmeUS2", ResourceType.LLM, "default", OpenAiChatModel.class)).isSameAs(acme);
        // 同类型多 key
        assertThat(router.get("acmeUS2", ResourceType.LLM, "backup", OpenAiChatModel.class)
                .defaultRequestParameters().modelName()).isEqualTo("gpt-acme-backup-us2");
    }

    @Test
    void getThrowsNotFoundForUnknownTenantTypeOrKey() {
        seed();
        registrar.loadAndRegister();

        assertThatThrownBy(() -> router.get("ghostUS2", ResourceType.LLM, "default", OpenAiChatModel.class))
                .isInstanceOf(TenantResourceNotFoundException.class)
                .hasMessageContaining("ghostUS2").hasMessageContaining("LLM").hasMessageContaining("default");

        assertThatThrownBy(() -> router.get("acmeUS2", ResourceType.EMBEDDING_STORE, "default", dev.langchain4j.store.embedding.EmbeddingStore.class))
                .isInstanceOf(TenantResourceNotFoundException.class);

        assertThatThrownBy(() -> router.get("acmeUS2", ResourceType.LLM, "missing", OpenAiChatModel.class))
                .isInstanceOf(TenantResourceNotFoundException.class);
    }

    @Test
    void getPrimaryResolvesPrimaryInstance() {
        seed();
        registrar.loadAndRegister();

        OpenAiChatModel primary = router.getPrimary("acmeUS2", ResourceType.LLM, OpenAiChatModel.class);
        assertThat(primary.defaultRequestParameters().modelName()).isEqualTo("gpt-acme-us2");
    }

    @Test
    void getPrimaryFallsBackToSingleInstance() {
        long solo = insertTenant("soloUS2");
        insertConfig(solo, "LLM", "only", false, """
                {"base-url":"https://api.solo.example.com/v1","api-key":"sk-us2","model-name":"gpt-solo-us2"}""");
        registrar.loadAndRegister();

        // 无 primary 且仅一条 → 回退（FR-011）
        assertThat(router.getPrimary("soloUS2", ResourceType.LLM, OpenAiChatModel.class)
                .defaultRequestParameters().modelName()).isEqualTo("gpt-solo-us2");
    }

    @Test
    void getPrimaryThrowsWhenNoPrimaryAndMultipleInstances() {
        seed();
        registrar.loadAndRegister();

        // acmeUS2 的 EMBEDDING_MODEL 无 primary 且有两条 → 抛异常（FR-011）
        assertThatThrownBy(() -> router.getPrimary("acmeUS2", ResourceType.EMBEDDING_MODEL, EmbeddingModel.class))
                .isInstanceOf(TenantResourceNotFoundException.class)
                .hasMessageContaining("No primary");
    }

    private void seed() {
        long acme = insertTenant("acmeUS2");
        long globex = insertTenant("globexUS2");
        insertConfig(acme, "LLM", "default", true, """
                {"base-url":"https://api.acme.example.com/v1","api-key":"sk-us2","model-name":"gpt-acme-us2"}""");
        insertConfig(acme, "LLM", "backup", false, """
                {"base-url":"https://api.acme.example.com/v1","api-key":"sk-us2","model-name":"gpt-acme-backup-us2"}""");
        insertConfig(acme, "EMBEDDING_MODEL", "a", false, """
                {"base-url":"https://api.acme.example.com/v1","api-key":"sk-us2","model-name":"emb-a-us2"}""");
        insertConfig(acme, "EMBEDDING_MODEL", "b", false, """
                {"base-url":"https://api.acme.example.com/v1","api-key":"sk-us2","model-name":"emb-b-us2"}""");
        insertConfig(globex, "LLM", "default", true, """
                {"base-url":"https://api.globex.example.com/v1","api-key":"sk-us2","model-name":"gpt-globex-us2"}""");
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

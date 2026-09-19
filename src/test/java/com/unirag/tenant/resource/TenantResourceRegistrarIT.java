package com.unirag.tenant.resource;

import com.unirag.AbstractPostgresIntegrationTest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 验收场景（spec.md）：启动加载两租户资源、空载启动、缺失类型不阻塞。
 * 真实连接本地 PG（research.md R12），租户码全局唯一（US1 后缀）。
 */
class TenantResourceRegistrarIT extends AbstractPostgresIntegrationTest {

    @Autowired
    TenantResourceRegistrar registrar;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void scenario1_twoTenantsEachGetTheirOwnBeans() {
        long acme = insertTenant("acmeUS1");
        long globex = insertTenant("globexUS1");
        insertConfig(acme, "LLM", "default", true, """
                {"base-url":"https://api.acme.example.com/v1","api-key":"sk-us1","model-name":"gpt-acme"}""");
        insertConfig(acme, "EMBEDDING_MODEL", "default", true, """
                {"base-url":"https://api.acme.example.com/v1","api-key":"sk-us1","model-name":"embed-acme"}""");
        insertConfig(acme, "EMBEDDING_STORE", "default", true, """
                {"host":"localhost","port":5432,"database":"uni_rag_db","user":"uni_rag_user",
                 "password":"x","table":"embeddings_acmeUS1","dimension":1024}""");
        insertConfig(globex, "LLM", "default", true, """
                {"base-url":"https://api.globex.example.com/v1","api-key":"sk-us1","model-name":"gpt-globex"}""");
        insertConfig(globex, "EMBEDDING_MODEL", "default", true, """
                {"base-url":"https://api.globex.example.com/v1","api-key":"sk-us1","model-name":"embed-globex"}""");

        registrar.loadAndRegister();

        // 每租户各自实例 + 命名规则 {type}_{tenantCode}_{key}
        assertThat(applicationContext.containsBean("llm_acmeUS1_default")).isTrue();
        assertThat(applicationContext.containsBean("embeddingModel_acmeUS1_default")).isTrue();
        assertThat(applicationContext.containsBean("embeddingStore_acmeUS1_default")).isTrue();
        assertThat(applicationContext.containsBean("llm_globexUS1_default")).isTrue();
        assertThat(applicationContext.containsBean("embeddingModel_globexUS1_default")).isTrue();

        // 参数各自生效（FR-003：不同实例、各自配置）
        OpenAiChatModel acmeLlm = applicationContext.getBean("llm_acmeUS1_default", OpenAiChatModel.class);
        OpenAiChatModel globexLlm = applicationContext.getBean("llm_globexUS1_default", OpenAiChatModel.class);
        assertThat(acmeLlm.defaultRequestParameters().modelName()).isEqualTo("gpt-acme");
        assertThat(globexLlm.defaultRequestParameters().modelName()).isEqualTo("gpt-globex");
        assertThat(acmeLlm).isNotSameAs(globexLlm);

        OpenAiEmbeddingModel acmeEmbed = applicationContext.getBean("embeddingModel_acmeUS1_default", OpenAiEmbeddingModel.class);
        assertThat(acmeEmbed.modelName()).isEqualTo("embed-acme");

        // 本地 PG 已安装 pgvector 扩展：EMBEDDING_STORE Bean 完整构建（create-table 默认 true，R12；
        // langchain4j 1.20 将建表 DDL 推迟到首次使用，构建期可安全验证）
        assertThat(applicationContext.getBean("embeddingStore_acmeUS1_default", EmbeddingStore.class))
                .isInstanceOf(EmbeddingStore.class);

        // 接口类型亦可解析（Router 场景预演）
        assertThat(applicationContext.getBean("llm_acmeUS1_default", ChatModel.class)).isSameAs(acmeLlm);
    }

    @Test
    void scenario3_missingResourceTypeDoesNotBlockStartup() {
        long only = insertTenant("onlyUS1");
        insertConfig(only, "LLM", "default", true, """
                {"base-url":"https://api.only.example.com/v1","api-key":"sk-us1","model-name":"gpt-only"}""");

        registrar.loadAndRegister();

        assertThat(applicationContext.containsBean("llm_onlyUS1_default")).isTrue();
        assertThat(applicationContext.containsBean("embeddingModel_onlyUS1_default")).isFalse();
        assertThat(applicationContext.containsBean("embeddingStore_onlyUS1_default")).isFalse();
    }

    @Test
    void scenario2_emptyTenantHasNoBeansAndStartupSucceeds() {
        registrar.loadAndRegister();

        assertThat(applicationContext.containsBean("llm_neverUS1_default")).isFalse();
        assertThat(applicationContext.containsBean("embeddingModel_neverUS1_default")).isFalse();
    }

    @Test
    void repeatedLoadIsIdempotent() {
        long acme = insertTenant("idemUS1");
        insertConfig(acme, "LLM", "default", true, """
                {"base-url":"https://api.idem.example.com/v1","api-key":"sk-us1","model-name":"gpt-idem"}""");

        registrar.loadAndRegister();
        Object first = applicationContext.getBean("llm_idemUS1_default");
        registrar.loadAndRegister();

        assertThat(applicationContext.getBean("llm_idemUS1_default")).isSameAs(first);
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

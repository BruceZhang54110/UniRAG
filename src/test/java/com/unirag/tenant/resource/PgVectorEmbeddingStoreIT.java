package com.unirag.tenant.resource;

import com.unirag.AbstractPostgresIntegrationTest;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US2 验收（spec.md FR-003/FR-005，contracts G1/G3/G4）：双租户向量表写入/检索隔离、
 * 幂等重载、已有表复用不重建、create-table:false 且表不存在时故障隔离。
 * 向量均为本地确定性构造，无外部模型凭据（FR-009）。租户码全局唯一（US4 后缀）。
 */
class PgVectorEmbeddingStoreIT extends AbstractPostgresIntegrationTest {

    private static final int DIMENSION = 1024;

    @Autowired
    TenantResourceRegistrar registrar;

    @Autowired
    TenantResourceRouter router;

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Environment environment;

    @Test
    void scenario1_twoTenantsWriteAndSearchIsolated() {
        long acme = insertTenant("acmeUS4");
        long globex = insertTenant("globexUS4");
        insertConfig(acme, "EMBEDDING_STORE", "default", true, storeConfig("embeddings_acmeUS4", true));
        insertConfig(globex, "EMBEDDING_STORE", "default", true, storeConfig("embeddings_globexUS4", true));

        registrar.loadAndRegister();

        // 开发库数据跨运行累积：清空本类表数据，保证条数断言确定性
        jdbcTemplate.update("DELETE FROM embeddings_acmeUS4");
        jdbcTemplate.update("DELETE FROM embeddings_globexUS4");

        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> acmeStore =
                router.get("acmeUS4", ResourceType.EMBEDDING_STORE, "default", EmbeddingStore.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> globexStore =
                router.get("globexUS4", ResourceType.EMBEDDING_STORE, "default", EmbeddingStore.class);

        acmeStore.add(embedding(1f, 0f), TextSegment.from("acmeUS4 知识库文档一"));
        acmeStore.add(embedding(0.9f, 0.1f), TextSegment.from("acmeUS4 知识库文档二"));
        globexStore.add(embedding(0f, 1f), TextSegment.from("globexUS4 知识库文档一"));
        globexStore.add(embedding(0.1f, 0.9f), TextSegment.from("globexUS4 知识库文档二"));

        // 本方检索：命中且 text 均属本方（FR-003 租户隔离，contracts G1）
        List<EmbeddingMatch<TextSegment>> acmeHits = search(acmeStore, embedding(1f, 0f));
        assertThat(acmeHits).hasSize(2);
        assertThat(acmeHits).allSatisfy(hit ->
                assertThat(hit.embedded().text()).contains("acmeUS4"));

        List<EmbeddingMatch<TextSegment>> globexHits = search(globexStore, embedding(0f, 1f));
        assertThat(globexHits).hasSize(2);
        assertThat(globexHits).allSatisfy(hit ->
                assertThat(hit.embedded().text()).contains("globexUS4"));

        // 以对方内容构造 query：本方库检索为空（跨租户 100% 隔离）
        assertThat(search(acmeStore, embedding(0f, 1f))).isEmpty();
        assertThat(search(globexStore, embedding(1f, 0f))).isEmpty();
    }

    @Test
    void scenario2_repeatedLoadIsIdempotent() {
        long acme = insertTenant("acmeUS4");
        insertConfig(acme, "EMBEDDING_STORE", "default", true, storeConfig("embeddings_acmeUS4", true));

        registrar.loadAndRegister();
        Object first = applicationContext.getBean("embeddingStore_acmeUS4_default");
        registrar.loadAndRegister();

        assertThat(applicationContext.getBean("embeddingStore_acmeUS4_default")).isSameAs(first);
    }

    @Test
    void scenario3_existingTableIsReusedNotRecreated() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS embeddings_reuseUS4 (
                    embedding_id UUID PRIMARY KEY,
                    embedding vector(1024),
                    text TEXT NULL,
                    metadata JSON NULL)""");
        // 开发库数据跨运行累积：清空后重新插入探针，保证断言确定性
        jdbcTemplate.update("DELETE FROM embeddings_reuseUS4");
        jdbcTemplate.update("""
                INSERT INTO embeddings_reuseUS4 (embedding_id, text)
                VALUES (gen_random_uuid(), 'probe-reuseUS4')""");

        long reuse = insertTenant("reuseUS4");
        insertConfig(reuse, "EMBEDDING_STORE", "default", true, storeConfig("embeddings_reuseUS4", true));

        registrar.loadAndRegister();

        assertThat(applicationContext.containsBean("embeddingStore_reuseUS4_default")).isTrue();
        // contracts G3：已存在表被复用而非重建，旧数据不受影响
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM embeddings_reuseUS4 WHERE text = 'probe-reuseUS4'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void scenario4_createTableFalseWithoutExistingTableFailsIsolated() {
        long acme = insertTenant("acmeUS4");
        long globex = insertTenant("globexUS4");
        long nocreate = insertTenant("nocreateUS4");
        insertConfig(acme, "EMBEDDING_STORE", "default", true, storeConfig("embeddings_acmeUS4", true));
        insertConfig(globex, "EMBEDDING_STORE", "default", true, storeConfig("embeddings_globexUS4", true));
        insertConfig(nocreate, "EMBEDDING_STORE", "default", true,
                storeConfig("embeddings_nocreateUS4", false));

        registrar.loadAndRegister();

        // FR-005 场景 4：create-table:false 且表不存在 → 该条构建失败、完全不注册（FR-010）
        assertThat(applicationContext.containsBean("embeddingStore_nocreateUS4_default")).isFalse();
        // 故障隔离不影响其他租户（FR-006）
        assertThat(applicationContext.containsBean("embeddingStore_acmeUS4_default")).isTrue();
        assertThat(applicationContext.containsBean("embeddingStore_globexUS4_default")).isTrue();
    }

    private static Embedding embedding(float first, float second) {
        float[] vector = new float[DIMENSION];
        vector[0] = first;
        vector[1] = second;
        return Embedding.from(vector);
    }

    private static List<EmbeddingMatch<TextSegment>> search(EmbeddingStore<TextSegment> store, Embedding query) {
        return store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(query)
                        .maxResults(10)
                        .minScore(0.99)
                        .build())
                .matches();
    }

    private String storeConfig(String table, boolean createTable) {
        return """
                {"host":"localhost","port":5432,"database":"uni_rag_db","user":"uni_rag_user",
                 "password":"%s","table":"%s","dimension":%d,"create-table":%b}"""
                .formatted(pgPassword(), table, DIMENSION, createTable);
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

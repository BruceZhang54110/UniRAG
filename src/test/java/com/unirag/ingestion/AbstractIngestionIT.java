package com.unirag.ingestion;

import com.unirag.AbstractPostgresIntegrationTest;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceIndex;
import com.unirag.tenant.resource.TenantResourceRegistrar;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 摄取集成测试基类：共享测试数据装配（租户/向量存储资源/摄取管线）与替身模型装配。
 *
 * <p>替身装配方案（tasks.md Notes）：{@code @MockBean TenantResourceRouter}——EMBEDDING_STORE 解析
 * 返回 004 Registrar 真实加载的 Bean，EMBEDDING_MODEL 解析返回 {@link DeterministicEmbeddingModel}，
 * 生产代码零分支。向量表命名 {@code embeddings_<租户码小写>[_粒度]}。
 */
public abstract class AbstractIngestionIT extends AbstractPostgresIntegrationTest {

    @Autowired
    protected TenantResourceRegistrar registrar;

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected Environment environment;

    @Autowired
    protected IngestionService ingestionService;

    @MockBean
    protected TenantResourceRouter tenantResourceRouter;

    /** 插入租户（幂等 upsert，复活语义对齐 003 测试约定）。 */
    protected long insertTenant(String code) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO tenant (tenant_code, name) VALUES (?, ?)
                ON CONFLICT (tenant_code) DO UPDATE SET name = EXCLUDED.name,
                    enabled = TRUE, deleted = FALSE
                RETURNING id""",
                Long.class, code, code);
    }

    /** 插入 EMBEDDING_STORE 资源（非主资源，避免触碰 003 主资源唯一索引；表不存在则由 004 机制建表）。 */
    protected void insertStoreConfig(long tenantId, String key, String table, int dimension) {
        jdbcTemplate.update("""
                INSERT INTO tenant_resource_config (tenant_id, resource_type, resource_key, is_primary, config)
                VALUES (?, 'EMBEDDING_STORE', ?, FALSE, ?::jsonb)
                ON CONFLICT (tenant_id, resource_type, resource_key) DO UPDATE
                SET is_primary = EXCLUDED.is_primary, config = EXCLUDED.config,
                    enabled = TRUE, deleted = FALSE""",
                tenantId, key, storeConfig(table, dimension));
    }

    /** 插入摄取管线（幂等 upsert，strategy 固定 RECURSIVE）。 */
    protected void insertPipeline(long tenantId, String pipelineKey, int maxTokens, int overlap,
                                  String storeKey, String modelKey) {
        jdbcTemplate.update("""
                INSERT INTO ingestion_pipeline
                    (tenant_id, pipeline_key, strategy, max_segment_tokens, overlap_tokens,
                     embedding_store_key, embedding_model_key, enabled)
                VALUES (?, ?, 'RECURSIVE', ?, ?, ?, ?, TRUE)
                ON CONFLICT (tenant_id, pipeline_key) DO UPDATE
                SET max_segment_tokens = EXCLUDED.max_segment_tokens,
                    overlap_tokens = EXCLUDED.overlap_tokens,
                    embedding_store_key = EXCLUDED.embedding_store_key,
                    embedding_model_key = EXCLUDED.embedding_model_key,
                    enabled = TRUE, deleted = FALSE""",
                tenantId, pipelineKey, maxTokens, overlap, storeKey, modelKey);
    }

    /** 清理本类租户的上期残留数据（开发库跨运行累积，保证条数断言确定性）。 */
    protected void cleanupTenant(String tenantCode, String... vectorTables) {
        Long tenantId = jdbcTemplate.query(
                "SELECT id FROM tenant WHERE tenant_code = ?", rs -> rs.next() ? rs.getLong(1) : null, tenantCode);
        if (tenantId != null) {
            jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id IN (SELECT id FROM document WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM ingestion_run WHERE document_id IN (SELECT id FROM document WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM document WHERE tenant_id = ?", tenantId);
        }
        for (String table : vectorTables) {
            // 首次运行时向量表可能尚未创建（004 机制在注册时才建表）
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            if (exists != null && exists > 0) {
                jdbcTemplate.update("DELETE FROM " + table);
            }
        }
    }

    /** 经 @MockBean Router 装配：模型 key → 确定性替身；store key → 真实向量表 Bean。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    protected void stubRouter(String tenantCode, Map<String, EmbeddingModel> models,
                              Map<String, EmbeddingStore<TextSegment>> stores) {
        models.forEach((modelKey, model) ->
                when(tenantResourceRouter.get(eq(tenantCode), eq(ResourceType.EMBEDDING_MODEL), eq(modelKey), eq(EmbeddingModel.class)))
                        .thenReturn(model));
        stores.forEach((storeKey, store) ->
                when(tenantResourceRouter.get(eq(tenantCode), eq(ResourceType.EMBEDDING_STORE), eq(storeKey), eq(EmbeddingStore.class)))
                        .thenReturn((EmbeddingStore) store));
    }

    /** 指定 store key 解析抛 TenantResourceNotFoundException（故障管线用）。 */
    protected void stubMissingStore(String tenantCode, String storeKey) {
        org.mockito.Mockito.when(tenantResourceRouter.get(
                        eq(tenantCode), eq(ResourceType.EMBEDDING_STORE), eq(storeKey), eq(EmbeddingStore.class)))
                .thenThrow(com.unirag.tenant.resource.TenantResourceNotFoundException
                        .notFound(tenantCode, ResourceType.EMBEDDING_STORE, storeKey));
    }

    /** 取 004 Registrar 真实注册的向量存储 Bean。 */
    @SuppressWarnings("unchecked")
    protected EmbeddingStore<TextSegment> storeBean(String tenantCode, String key) {
        return applicationContext.getBean(
                TenantResourceIndex.beanNameOf(ResourceType.EMBEDDING_STORE, tenantCode, key),
                EmbeddingStore.class);
    }

    private String storeConfig(String table, int dimension) {
        return """
                {"host":"localhost","port":5432,"database":"uni_rag_db","user":"uni_rag_user",
                 "password":"%s","table":"%s","dimension":%d,"create-table":true}"""
                .formatted(pgPassword(), table, dimension);
    }

    private String pgPassword() {
        return environment.getProperty("spring.datasource.password");
    }
}

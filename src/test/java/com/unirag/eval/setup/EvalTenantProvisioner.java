package com.unirag.eval.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unirag.eval.RunMode;
import com.unirag.eval.dataset.DatasetConfig;
import com.unirag.eval.dataset.DatasetValidationException;
import com.unirag.eval.dataset.EvaluationDataset;
import com.unirag.eval.dataset.PipelineConfig;
import com.unirag.eval.dataset.StoreConfig;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceIndex;
import com.unirag.tenant.resource.TenantResourceRegistrar;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 评测租户、资源与管线装配（013 research R3）。 */
public final class EvalTenantProvisioner {

    public static final String ENV_BASE_URL = "EVAL_EMBEDDING_BASE_URL";

    public static final String ENV_API_KEY = "EVAL_EMBEDDING_API_KEY";

    public static final String ENV_MODEL = "EVAL_EMBEDDING_MODEL";

    public static final String ENV_DIMENSION = "EVAL_EMBEDDING_DIMENSION";

    private final JdbcTemplate jdbcTemplate;

    private final Environment environment;

    private final TenantResourceRegistrar registrar;

    private final ApplicationContext applicationContext;

    private final ObjectMapper mapper;

    public EvalTenantProvisioner(JdbcTemplate jdbcTemplate,
                                 Environment environment,
                                 TenantResourceRegistrar registrar,
                                 ApplicationContext applicationContext,
                                 ObjectMapper mapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.registrar = registrar;
        this.applicationContext = applicationContext;
        this.mapper = mapper;
    }

    public long provision(EvaluationDataset dataset, RunMode mode) {
        return provision(dataset, mode, false);
    }

    /**
     * @param useSourceModel true 表示复用数据库中已有租户的 EMBEDDING_MODEL，跳过环境变量与评测租户模型配置
     */
    public long provision(EvaluationDataset dataset, RunMode mode, boolean useSourceModel) {
        DatasetConfig config = dataset.config();
        if (mode.usesExternalModel() && !useSourceModel) {
            requireEnv(ENV_BASE_URL);
            requireEnv(ENV_API_KEY);
            requireEnv(ENV_MODEL);
            requireEnv(ENV_DIMENSION);
            int dimension = Integer.parseInt(environment.getProperty(ENV_DIMENSION));
            if (dimension != config.store().dimension()) {
                throw new DatasetValidationException("V-009",
                        "EVAL_EMBEDDING_DIMENSION=" + dimension
                                + " 与 store.dimension=" + config.store().dimension() + " 不一致");
            }
        }

        if (config.retrieval().rewrite()) {
            throw new DatasetValidationException("V-010",
                    "评测运行暂未配置 QUERY_REWRITE_MODEL 资源；请关闭 rewrite");
        }
        if (config.retrieval().rerank() && mode.usesExternalModel() && !useSourceModel) {
            throw new DatasetValidationException("V-010",
                    "启用 rerank 时必须通过 eval.embeddingTenant 复用源租户的 RERANK_MODEL");
        }

        long tenantId = upsertTenant(config.tenant());
        cleanup(tenantId, storeTables(config));
        insertStoreConfigs(tenantId, config);
        if (mode.usesExternalModel() && !useSourceModel) {
            insertModelConfigs(tenantId, config);
        }
        insertPipelines(tenantId, config);
        registrar.loadAndRegister();
        return tenantId;
    }

    /** 从数据库中已有租户解析真实嵌入模型；不复制、不打印任何配置内容。 */
    public EmbeddingModel resolveSourceEmbeddingModel(String tenantCode, String modelKey) {
        registrar.loadAndRegister();
        String key = modelKey == null || modelKey.isBlank()
                ? primaryEmbeddingModelKey(tenantCode) : modelKey;
        try {
            return applicationContext.getBean(TenantResourceIndex.beanNameOf(
                    ResourceType.EMBEDDING_MODEL, tenantCode, key), EmbeddingModel.class);
        } catch (RuntimeException e) {
            throw new DatasetValidationException("V-010",
                    "源租户嵌入模型不可用: tenant=" + tenantCode + ", key=" + key
                            + ", reason=" + e.getClass().getSimpleName());
        }
    }

    private String primaryEmbeddingModelKey(String tenantCode) {
        List<String> keys = jdbcTemplate.queryForList("""
                SELECT trc.resource_key
                FROM tenant_resource_config trc
                JOIN tenant t ON t.id = trc.tenant_id
                WHERE t.tenant_code = ? AND trc.resource_type = 'EMBEDDING_MODEL'
                  AND trc.is_primary = TRUE AND trc.enabled = TRUE AND trc.deleted = FALSE
                ORDER BY trc.id
                LIMIT 1""", String.class, tenantCode);
        return keys.stream().findFirst().orElseThrow(() -> new DatasetValidationException("V-010",
                "源租户未配置 primary EMBEDDING_MODEL: " + tenantCode));
    }

    /** 从数据库中已有租户解析真实重排模型；不复制、不打印任何配置内容。 */
    public ScoringModel resolveSourceRerankModel(String tenantCode) {
        registrar.loadAndRegister();
        String key = primaryRerankModelKey(tenantCode);
        try {
            return applicationContext.getBean(TenantResourceIndex.beanNameOf(
                    ResourceType.RERANK_MODEL, tenantCode, key), ScoringModel.class);
        } catch (RuntimeException e) {
            throw new DatasetValidationException("V-010",
                    "源租户重排模型不可用: tenant=" + tenantCode + ", key=" + key
                            + ", reason=" + e.getClass().getSimpleName());
        }
    }

    private String primaryRerankModelKey(String tenantCode) {
        List<String> keys = jdbcTemplate.queryForList("""
                SELECT trc.resource_key
                FROM tenant_resource_config trc
                JOIN tenant t ON t.id = trc.tenant_id
                WHERE t.tenant_code = ? AND trc.resource_type = 'RERANK_MODEL'
                  AND trc.is_primary = TRUE AND trc.enabled = TRUE AND trc.deleted = FALSE
                ORDER BY trc.id
                LIMIT 1""", String.class, tenantCode);
        return keys.stream().findFirst().orElseThrow(() -> new DatasetValidationException("V-010",
                "源租户未配置 primary RERANK_MODEL: " + tenantCode));
    }

    public Map<String, EmbeddingModel> resolveEmbeddingModels(EvaluationDataset dataset) {
        Map<String, EmbeddingModel> models = new LinkedHashMap<>();
        for (String modelKey : dataset.config().pipelines().stream()
                .map(PipelineConfig::modelKey).distinct().toList()) {
            models.put(modelKey, applicationContext.getBean(
                    TenantResourceIndex.beanNameOf(ResourceType.EMBEDDING_MODEL,
                            dataset.config().tenant(), modelKey),
                    EmbeddingModel.class));
        }
        return models;
    }

    /** 将评测模型显式覆盖到真实 Router 上；Store 等资源仍由父类按真实索引解析。 */
    public void wireRouter(EvalTenantResourceRouter router, EvaluationDataset dataset,
                           Map<String, EmbeddingModel> embeddingModels, ScoringModel rerankModel) {
        String tenant = dataset.config().tenant();
        embeddingModels.forEach((key, model) -> router.overrideEmbeddingModel(tenant, key, model));
        if (rerankModel != null) {
            router.overrideRerankModel(tenant, rerankModel);
        }
    }

    private long upsertTenant(String tenantCode) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tenant (tenant_code, name) VALUES (?, ?)
                ON CONFLICT (tenant_code) DO UPDATE
                SET name = EXCLUDED.name, enabled = TRUE, deleted = FALSE
                RETURNING id""", Long.class, tenantCode, "RAG evaluation: " + tenantCode);
    }

    private void cleanup(long tenantId, List<String> tables) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id IN "
                + "(SELECT id FROM document WHERE tenant_id = ?)", tenantId);
        jdbcTemplate.update("DELETE FROM ingestion_run WHERE document_id IN "
                + "(SELECT id FROM document WHERE tenant_id = ?)", tenantId);
        jdbcTemplate.update("DELETE FROM document_ingestion_job WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM document_ingestion_version WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM document WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_resource_config WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM ingestion_pipeline WHERE tenant_id = ?", tenantId);
        for (String table : tables) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, table);
            if (exists != null && exists > 0) {
                jdbcTemplate.update("DELETE FROM " + table);
            }
        }
    }

    private void insertStoreConfigs(long tenantId, DatasetConfig config) {
        if (config.stores().isEmpty()) {
            insertStoreConfig(tenantId, config.pipelines().get(0).storeKey(), config.store());
            return;
        }
        config.stores().forEach((storeKey, store) -> insertStoreConfig(tenantId, storeKey, store));
    }

    private void insertStoreConfig(long tenantId, String storeKey, StoreConfig storeConfig) {
        ObjectNode store = mapper.createObjectNode();
        store.put("host", environment.getProperty("embedding-store.host", "localhost"));
        store.put("port", Integer.parseInt(environment.getProperty("embedding-store.port", "5432")));
        store.put("database", environment.getProperty("embedding-store.database", "uni_rag_db"));
        store.put("user", environment.getProperty("embedding-store.user", "uni_rag_user"));
        store.put("password", environment.getProperty("embedding-store.password", ""));
        store.put("table", storeConfig.table());
        store.put("dimension", storeConfig.dimension());
        store.put("create-table", true);
        jdbcTemplate.update("""
                INSERT INTO tenant_resource_config
                    (tenant_id, resource_type, resource_key, is_primary, config, enabled, deleted)
                VALUES (?, 'EMBEDDING_STORE', ?, FALSE, ?::jsonb, TRUE, FALSE)
                ON CONFLICT (tenant_id, resource_type, resource_key) DO UPDATE
                SET config = EXCLUDED.config, enabled = TRUE, deleted = FALSE""",
                tenantId, storeKey, store.toString());
    }

    private static List<String> storeTables(DatasetConfig config) {
        if (!config.stores().isEmpty()) {
            return config.stores().values().stream().map(StoreConfig::table).distinct().toList();
        }
        return List.of(config.store().table());
    }

    private void insertModelConfigs(long tenantId, DatasetConfig config) {
        for (String modelKey : config.pipelines().stream().map(PipelineConfig::modelKey).distinct().toList()) {
            ObjectNode model = mapper.createObjectNode();
            model.put("base-url", environment.getProperty(ENV_BASE_URL));
            model.put("api-key", environment.getProperty(ENV_API_KEY));
            model.put("model-name", environment.getProperty(ENV_MODEL));
            jdbcTemplate.update("""
                    INSERT INTO tenant_resource_config
                        (tenant_id, resource_type, resource_key, is_primary, config, enabled, deleted)
                    VALUES (?, 'EMBEDDING_MODEL', ?, FALSE, ?::jsonb, TRUE, FALSE)
                    ON CONFLICT (tenant_id, resource_type, resource_key) DO UPDATE
                    SET config = EXCLUDED.config, enabled = TRUE, deleted = FALSE""",
                    tenantId, modelKey, model.toString());
        }
    }

    private void insertPipelines(long tenantId, DatasetConfig config) {
        for (PipelineConfig pipeline : config.pipelines()) {
            jdbcTemplate.update("""
                    INSERT INTO ingestion_pipeline
                        (tenant_id, pipeline_key, strategy, max_segment_tokens, overlap_tokens,
                         embedding_store_key, embedding_model_key, enabled, deleted)
                    VALUES (?, ?, 'RECURSIVE', ?, ?, ?, ?, TRUE, FALSE)
                    ON CONFLICT (tenant_id, pipeline_key) DO UPDATE
                    SET strategy = EXCLUDED.strategy,
                        max_segment_tokens = EXCLUDED.max_segment_tokens,
                        overlap_tokens = EXCLUDED.overlap_tokens,
                        embedding_store_key = EXCLUDED.embedding_store_key,
                        embedding_model_key = EXCLUDED.embedding_model_key,
                        enabled = TRUE, deleted = FALSE""",
                    tenantId, pipeline.key(), pipeline.maxSegmentTokens(), pipeline.overlapTokens(),
                    pipeline.storeKey(), pipeline.modelKey());
        }
    }

    private void requireEnv(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new DatasetValidationException("V-009", "缺少环境变量 " + name);
        }
    }
}

package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 默认阈值接线验收（contracts/retrieval-api.md §2）：调用方省略 {@code minScore} 时使用
 * {@code unirag.retrieval.default-min-score}。这里覆盖为 0.99，确定性嵌入模型的命中应被全部过滤。
 *
 * <p>租户码全局唯一：{@code retrDefault}。
 */
class RetrievalDefaultMinScoreIT extends AbstractRetrievalIT {

    private static final String TENANT = "retrDefault";
    private static final String TABLE = "embeddings_retrdefault_main";

    @DynamicPropertySource
    static void defaultMinScore(DynamicPropertyRegistry registry) {
        registry.add("unirag.retrieval.default-min-score", () -> "0.99");
    }

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(
                TENANT, "default-doc", null, IngestionTestDocuments.longChineseDocument()));
    }

    @Test
    void 省略minScore时使用配置的默认阈值() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"向量数据库\",\"topK\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunks", hasSize(0)));
    }

    @Test
    void 显式minScore优先于配置默认值() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"向量数据库\",\"topK\":2,\"minScore\":0.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunks", hasSize(2)));
    }
}

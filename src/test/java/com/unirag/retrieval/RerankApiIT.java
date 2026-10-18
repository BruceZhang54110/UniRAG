package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.tenant.resource.ResourceType;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.scoring.ScoringModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 011 验收（spec US1/US2/US3）：重排启用时问答/检索只保留相关片段，并产生 RERANK 观测行；
 * 未配置重排的租户行为不变；重排失败按 fail-open 回退。
 *
 * <p>租户码全局唯一：{@code rerankApi}（有重排）/ {@code rerankNone}（无重排）。
 */
class RerankApiIT extends AbstractRetrievalIT {

    private static final String TENANT = "rerankApi";
    private static final String TABLE = "embeddings_rerankapi_main";
    private static final String VOICE_DOC = "语音转写文档";
    private static final String DEMO_DOC = "demo-doc-1";

    private static final String NONE_TENANT = "rerankNone";
    private static final String NONE_TABLE = "embeddings_reranknone_main";
    private static final String NONE_DOC = "none-doc";

    private OpenAiChatModel chatModel;

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
        ingestionService.ingest(new IngestRequest(TENANT, VOICE_DOC, null,
                "实时语音转写大模型通过 WebSocket 实时传输语音，支持方言、并发与套餐限制。"));
        ingestionService.ingest(new IngestRequest(TENANT, DEMO_DOC, null,
                "UniRAG 演示文档介绍多租户 RAG 知识库、文档上传、切片与向量检索。"));

        stubRerankModel(TENANT, segment -> segment.text().contains("实时语音") ? 0.99 : 0.10);
        chatModel = stubChatModel(TENANT);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse("回答。[1]"));

        cleanupTenant(NONE_TENANT, NONE_TABLE);
        cleanupObservations(NONE_TENANT);
        long noneTenantId = insertTenant(NONE_TENANT);
        insertStoreConfig(noneTenantId, "mainStore", NONE_TABLE, 1024);
        insertPipeline(noneTenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(NONE_TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(NONE_TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(NONE_TENANT, NONE_DOC, null,
                "没有重排配置的租户文档。"));
    }

    @Test
    void 问答引用只保留重排后的相关片段() throws Exception {
        mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"实时语音转写大模型讲了什么？","topK":5,"rerank":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations", hasSize(1)))
                .andExpect(jsonPath("$.data.citations[0].docName").value(VOICE_DOC))
                .andExpect(jsonPath("$.data.citations[0].rerankScore").value(0.99))
                .andExpect(jsonPath("$.data.citations[0].score").isNumber());
    }

    @Test
    void 检索接口按重排结果返回并记录观测() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"实时语音转写大模型","topK":5,"rerank":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunks", hasSize(1)))
                .andExpect(jsonPath("$.data.chunks[0].docName").value(VOICE_DOC))
                .andExpect(jsonPath("$.data.chunks[0].rerankScore").value(0.99))
                .andExpect(jsonPath("$.data.chunks[0].score").isNumber());

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT hit_count, item_count, status, detail
                FROM retrieval_observation
                WHERE tenant_id = ? AND stage = 'RERANK'
                ORDER BY id DESC LIMIT 1
                """, tenantId(TENANT));
        assertThat(row.get("hit_count")).isEqualTo(2);
        assertThat(row.get("item_count")).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat((String) row.get("detail")).contains("keep").contains("drop");
    }

    @Test
    void 显式关闭重排时返回原向量结果() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"实时语音转写大模型","topK":2,"rerank":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunks", hasSize(2)));
    }

    @Test
    void 重排失败时主链路成功并记录降级() throws Exception {
        ScoringModel failing = stubRerankModel(TENANT, segment -> 0.0);
        when(failing.scoreAll(anyList(), anyString())).thenThrow(new IllegalStateException("rerank down"));

        mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"实时语音转写大模型讲了什么？","topK":5,"rerank":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations").isNotEmpty());

        String status = jdbcTemplate.queryForObject("""
                SELECT status FROM retrieval_observation
                WHERE tenant_id = ? AND stage = 'RERANK'
                ORDER BY id DESC LIMIT 1
                """, String.class, tenantId(TENANT));
        assertThat(status).isEqualTo("DEGRADED");
    }

    @Test
    void 未配置重排的租户行为不变() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve")
                        .header("X-Tenant-Code", NONE_TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"文档","topK":5,"rerank":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunks", hasSize(1)))
                .andExpect(jsonPath("$.data.chunks[0].rerankScore").value(nullValue()));

        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM retrieval_observation
                WHERE tenant_id = ? AND stage = 'RERANK'
                """, Integer.class, tenantId(NONE_TENANT));
        assertThat(count).isZero();
        assertThat(tenantResourceRouter.hasPrimary(NONE_TENANT, ResourceType.RERANK_MODEL)).isFalse();
    }
}

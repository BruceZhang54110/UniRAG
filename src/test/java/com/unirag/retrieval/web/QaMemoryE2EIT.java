package com.unirag.retrieval.web;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.AbstractRetrievalIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US2 端到端验收（tasks.md T014，FR-001/003）：走 HTTP {@code POST /api/v1/ask}
 * （X-Tenant-Code + sessionId），两轮问答后 chat_memory 表一行且消息逐轮累积。
 *
 * <p>租户码全局唯一：{@code memE2E}。
 */
class QaMemoryE2EIT extends AbstractRetrievalIT {

    private static final String TENANT = "memE2E";
    private static final String TABLE = "embeddings_meme2e_main";
    private static final String DOC = "mem-e2e-doc";
    private static final String SESSION = "s-e2e-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        jdbcTemplate.update("DELETE FROM chat_memory WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                TENANT);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));
        when(stubChatModel(TENANT).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenAnswer(invocation -> chatResponse("基于资料的回答"));
    }

    @Test
    void 两轮问答后记忆逐轮累积为一行() throws Exception {
        ask("重排模型有哪些？");
        ask("默认超时是多少？");

        String memoryId = tenantId(TENANT) + ":" + SESSION;
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_memory WHERE memory_id = ?", Long.class, memoryId))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(messages::jsonb) FROM chat_memory WHERE memory_id = ?",
                Integer.class, memoryId))
                .isEqualTo(4);
    }

    private void ask(String question) throws Exception {
        mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "%s", "sessionId": "%s", "topK": 3}
                                """.formatted(question, SESSION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId").isString())
                .andExpect(jsonPath("$.data.answer").isString());
    }
}

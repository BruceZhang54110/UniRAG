package com.unirag.retrieval.web;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.retrieval.pipeline.StageName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US4 清空接口验收（tasks.md T019，FR-009，contracts/clear-memory-endpoint.md）：
 * DELETE 成功且表行删除、幂等、清空后可再问且不引用历史、清空动作留观测。
 *
 * <p>租户码全局唯一：{@code memClr}。
 */
class ChatMemoryControllerIT extends AbstractRetrievalIT {

    private static final String TENANT = "memClr";
    private static final String TABLE = "embeddings_memclr_main";
    private static final String DOC = "mem-clr-doc";
    private static final String SESSION = "s-clr-1";

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
        ask("重排模型有哪些？");
        assertThat(memoryRows()).isOne();
    }

    @Test
    void 清空成功幂等且留观测() throws Exception {
        mockMvc.perform(delete("/api/v1/chat-memories/{sessionId}", SESSION)
                        .header("X-Tenant-Code", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(memoryRows()).isZero();

        // 幂等：再次清空仍 200
        mockMvc.perform(delete("/api/v1/chat-memories/{sessionId}", SESSION)
                        .header("X-Tenant-Code", TENANT))
                .andExpect(status().isOk());

        // 清空动作留痕：CHAT_MEMORY / SUCCESS / cleared=true
        Integer observations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retrieval_observation WHERE tenant_id = ? AND stage = ? "
                        + "AND detail LIKE '%\"cleared\":true%'",
                Integer.class, tenantId(TENANT), StageName.CHAT_MEMORY.name());
        assertThat(observations).isEqualTo(2);
    }

    @Test
    void 清空后再问不引用历史() throws Exception {
        mockMvc.perform(delete("/api/v1/chat-memories/{sessionId}", SESSION)
                        .header("X-Tenant-Code", TENANT))
                .andExpect(status().isOk());

        // 同会话再问：LLM 收到的消息不含任何历史（只有 System + 本轮 User）
        ask("默认超时是多少？");
        String memoryId = tenantId(TENANT) + ":" + SESSION;
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(messages::jsonb) FROM chat_memory WHERE memory_id = ?",
                Integer.class, memoryId))
                .isEqualTo(2);
    }

    @Test
    void 缺失租户头走统一错误() throws Exception {
        mockMvc.perform(delete("/api/v1/chat-memories/{sessionId}", SESSION))
                .andExpect(status().isBadRequest());
    }

    private void ask(String question) throws Exception {
        mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "%s", "sessionId": "%s", "topK": 3}
                                """.formatted(question, SESSION)))
                .andExpect(status().isOk());
    }

    private Long memoryRows() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_memory WHERE memory_id = ?", Long.class,
                tenantId(TENANT) + ":" + SESSION);
    }
}

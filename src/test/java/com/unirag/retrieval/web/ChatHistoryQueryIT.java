package com.unirag.retrieval.web;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.AbstractRetrievalIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US2 验收（tasks.md T009，FR-002/003）：分页正序回放、空会话 200 / 未知会话 404、
 * 租户头校验、跨租户 404 无泄露。
 *
 * <p>租户码全局唯一：{@code histQry}。
 */
class ChatHistoryQueryIT extends AbstractRetrievalIT {

    private static final String TENANT = "histQry";
    private static final String TABLE = "embeddings_histqry_main";
    private static final String DOC = "hist-qry-doc";
    private static final String SESSION = "s-hist-qry";

    @BeforeEach
    void setUp() throws Exception {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        jdbcTemplate.update("DELETE FROM chat_memory WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                TENANT);
        jdbcTemplate.update("DELETE FROM chat_message WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
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
                .thenReturn(chatResponse("基于资料的回答"));
        for (String question : new String[]{"问题一", "问题二", "问题三"}) {
            ask(question);
        }
    }

    @Test
    void 分页正序回放且拼接与全量一致() throws Exception {
        mockMvc.perform(get("/api/v1/chat-memories/{sessionId}/messages", SESSION)
                        .param("page", "0").param("size", "2")
                        .header("X-Tenant-Code", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].role").value("USER"))
                .andExpect(jsonPath("$.data.items[0].content").value("问题一"))
                .andExpect(jsonPath("$.data.items[1].roundSeq").value(1));

        mockMvc.perform(get("/api/v1/chat-memories/{sessionId}/messages", SESSION)
                        .param("page", "2").param("size", "2")
                        .header("X-Tenant-Code", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].content").value("问题三"))
                .andExpect(jsonPath("$.data.items[1].status").value("SUCCESS"));
    }

    @Test
    void 存在但无消息的会话200空列表未知会话404跨租户404() throws Exception {
        // 会话已存在（有记忆行）但历史为空 → 200 空列表
        String emptyButExisting = "empty-but-existing";
        jdbcTemplate.update("INSERT INTO chat_memory (memory_id, tenant_id, session_id, messages) "
                        + "VALUES (?, ?, ?, '[]'::text) ON CONFLICT (memory_id) DO NOTHING",
                tenantId(TENANT) + ":" + emptyButExisting, tenantId(TENANT), emptyButExisting);
        mockMvc.perform(get("/api/v1/chat-memories/{sessionId}/messages", emptyButExisting)
                        .header("X-Tenant-Code", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());

        mockMvc.perform(get("/api/v1/chat-memories/{sessionId}/messages", "no-such-session")
                        .header("X-Tenant-Code", TENANT))
                .andExpect(status().isNotFound());

        String otherTenant = "histOther";
        cleanupTenant(otherTenant);
        insertTenant(otherTenant);
        mockMvc.perform(get("/api/v1/chat-memories/{sessionId}/messages", SESSION)
                        .header("X-Tenant-Code", otherTenant))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/chat-memories/{sessionId}/messages", SESSION))
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
}

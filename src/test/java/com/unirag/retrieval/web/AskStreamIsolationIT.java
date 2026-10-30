package com.unirag.retrieval.web;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.tenant.resource.ResourceType;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US3 隔离验收（tasks.md T020，FR-002/007，宪法 IV）：两租户共用同一 sessionId 走流式端点，
 * 记忆严格互不可见、各租户的流式模型只收到本租户的问题。
 *
 * <p>租户码全局唯一：{@code sseIsoA} / {@code sseIsoB}。
 */
class AskStreamIsolationIT extends AbstractRetrievalIT {

    private static final String TENANT_A = "sseIsoA";
    private static final String TENANT_B = "sseIsoB";
    private static final String TABLE_A = "embeddings_sseisoa_main";
    private static final String TABLE_B = "embeddings_sseisob_main";
    private static final String SHARED_SESSION = "s-shared-stream";

    private final List<String> seenByA = new CopyOnWriteArrayList<>();
    private final List<String> seenByB = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        for (String tenant : new String[]{TENANT_A, TENANT_B}) {
            String table = tenant.equals(TENANT_A) ? TABLE_A : TABLE_B;
            cleanupTenant(tenant, table);
            cleanupObservations(tenant);
            jdbcTemplate.update(
                    "DELETE FROM chat_memory WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                    tenant);
            long tenantId = insertTenant(tenant);
            insertStoreConfig(tenantId, "mainStore", table, 1024);
            insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        }
        registrar.loadAndRegister();
        stubRouter(TENANT_A, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT_A, "mainStore")));
        stubRouter(TENANT_B, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT_B, "mainStore")));
        ingestionService.ingest(new IngestRequest(
                TENANT_A, "iso-doc-a", null, IngestionTestDocuments.longChineseDocument()));
        ingestionService.ingest(new IngestRequest(
                TENANT_B, "iso-doc-b", null, IngestionTestDocuments.longChineseDocument()));

        recordingStreamingModel(TENANT_A, seenByA, "租户A的回答");
        recordingStreamingModel(TENANT_B, seenByB, "租户B的回答");
    }

    @Test
    void 同sessionId跨租户流式问答记忆互不可见且输入隔离() throws Exception {
        streamAsk(TENANT_A, "租户A的保密问题？");
        streamAsk(TENANT_B, "它指的是什么？");

        // 存储层：各自 memoryId 行独立
        assertThat(countMemoryRows(TENANT_A)).isOne();
        assertThat(countMemoryRows(TENANT_B)).isOne();

        // 输入隔离：每个租户只收到自己的问题，绝无对方内容
        assertThat(String.join(",", seenByA)).contains("租户A的保密问题？").doesNotContain("它指的是什么？");
        assertThat(String.join(",", seenByB)).contains("它指的是什么？").doesNotContain("租户A的保密问题？");
    }

    private void recordingStreamingModel(String tenant, List<String> seen, String answerText) {
        OpenAiStreamingChatModel model = org.mockito.Mockito.mock(OpenAiStreamingChatModel.class);
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            seen.add(request.messages().toString());
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse(answerText);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(answerText)).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(tenantResourceRouter.getPrimary(eq(tenant), eq(ResourceType.STREAMING_LLM),
                eq(OpenAiStreamingChatModel.class))).thenReturn(model);
    }

    private void streamAsk(String tenant, String question) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/v1/ask/stream")
                        .header("X-Tenant-Code", tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "%s", "sessionId": "%s", "topK": 3}
                                """.formatted(question, SHARED_SESSION)))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }

    private Long countMemoryRows(String tenantCode) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_memory WHERE memory_id = ?", Long.class,
                tenantId(tenantCode) + ":" + SHARED_SESSION);
    }
}

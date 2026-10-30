package com.unirag.retrieval.web;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.tenant.resource.ResourceType;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US1 错误路径验收（tasks.md T010，FR-002，SC-005）：业务失败以 error 事件收尾且连接正常
 * 关闭；文案不含凭据；租户头缺失/未知走统一 HTTP 错误（与非流式一致，无事件流）。
 *
 * <p>租户码全局唯一：{@code sseErr}。
 */
class AskStreamEndpointErrorIT extends AbstractRetrievalIT {

    private static final String TENANT = "sseErr";
    private static final String TABLE = "embeddings_sseerr_main";
    private static final String DOC = "sse-err-doc";
    private static final String FAKE_SECRET = "sk-stream-secret-456";

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
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));

        OpenAiStreamingChatModel streamingModel = org.mockito.Mockito.mock(OpenAiStreamingChatModel.class);
        doAnswer(invocation -> {
            dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onError(new IllegalStateException("connect timed out (api-key=" + FAKE_SECRET + ")"));
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class),
                any(dev.langchain4j.model.chat.response.StreamingChatResponseHandler.class));
        doThrow(new AssertionError("同步 LLM 不应被解析"))
                .when(tenantResourceRouter)
                .getPrimary(eq(TENANT), eq(ResourceType.LLM),
                        eq(dev.langchain4j.model.openai.OpenAiChatModel.class));
        when(tenantResourceRouter.getPrimary(eq(TENANT), eq(ResourceType.STREAMING_LLM),
                eq(OpenAiStreamingChatModel.class))).thenReturn(streamingModel);
    }

    @Test
    void 模型失败时error事件收尾且文案不含凭据() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/v1/ask/stream")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"重排模型有哪些？\", \"topK\": 3}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult async = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        String body = async.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).contains("event:error").doesNotContain("event:done").doesNotContain("event:message");
        assertThat(body).contains("LLM_UNAVAILABLE");
        assertThat(body).doesNotContain(FAKE_SECRET);
    }

    @Test
    void 缺失租户头直接400且无事件流() throws Exception {
        mockMvc.perform(post("/api/v1/ask/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"重排模型有哪些？\"}"))
                .andExpect(status().isBadRequest());
    }
}

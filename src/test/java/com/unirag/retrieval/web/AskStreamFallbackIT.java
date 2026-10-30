package com.unirag.retrieval.web;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.AbstractRetrievalIT;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US3 缓冲降级验收（tasks.md T018，research.md R3）：租户未配置 STREAMING_LLM 时，
 * 流式端点不报错——同步生成后单条 message（整段）+ done，协议与字段不变。
 *
 * <p>租户码全局唯一：{@code sseFb}。
 */
class AskStreamFallbackIT extends AbstractRetrievalIT {

    private static final String TENANT = "sseFb";
    private static final String TABLE = "embeddings_ssefb_main";
    private static final String DOC = "sse-fb-doc";

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
        // 仅配置同步 LLM；STREAMING_LLM 未配置（router 未打桩 → 解析抛异常 → 缓冲降级）
        when(tenantResourceRouter.getPrimary(eq(TENANT), eq(com.unirag.tenant.resource.ResourceType.LLM),
                eq(OpenAiChatModel.class))).thenAnswer(invocation -> {
            OpenAiChatModel model = org.mockito.Mockito.mock(OpenAiChatModel.class);
            when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("同步整段回答。[1]")).build());
            return model;
        });
    }

    @Test
    void 未配流式模型时单条缓冲message加done() throws Exception {
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

        assertThat(body).contains("event:message").contains("event:done").doesNotContain("event:error");
        assertThat(body).contains("同步整段回答。[1]");
        // 缓冲降级：整段回答在 done 中同样完整（done 仍是权威来源）
        assertThat(body).contains("\"answer\":\"同步整段回答。[1]\"");
    }
}

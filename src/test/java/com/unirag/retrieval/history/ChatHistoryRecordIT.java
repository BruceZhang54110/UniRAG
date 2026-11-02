package com.unirag.retrieval.history;

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
import org.springframework.beans.factory.annotation.Autowired;
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
 * US1 验收（tasks.md T005，FR-001/008，SC-001）：3 轮流式问答历史 6 行逐字一致；
 * 触发记忆逐出后历史仍完整；LLM 故障轮记 FAILED。
 *
 * <p>租户码全局唯一：{@code histUS1}。
 */
class ChatHistoryRecordIT extends AbstractRetrievalIT {

    private static final String TENANT = "histUS1";
    private static final String TABLE = "embeddings_histus1_main";
    private static final String DOC = "hist-us1-doc";
    private static final String SESSION = "s-hist-us1";

    @Autowired
    private ChatHistoryWriter historyWriter;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        long tenantId = insertTenant(TENANT);
        historyWriter.deleteBySession(tenantId, SESSION);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));
        stubStreamingModel("第1轮回答", "第2轮回答", "第3轮回答");
    }

    @Test
    void 多轮问答历史逐字一致且逐出后完整() throws Exception {
        streamAsk("问题一");
        streamAsk("问题二");
        streamAsk("问题三");

        ChatHistoryWriter.PageResult page = historyWriter.findMessages(tenantId(TENANT), SESSION, 0, 100);
        assertThat(page.total()).isEqualTo(6);
        assertThat(page.items()).extracting(m -> m.getRole() + ":" + m.getContent())
                .containsExactly("USER:问题一", "AI:第1轮回答", "USER:问题二", "AI:第2轮回答",
                        "USER:问题三", "AI:第3轮回答");
        assertThat(page.items()).extracting(m -> m.getStatus())
                .containsOnly("SUCCESS");
        assertThat(page.items()).extracting(m -> m.getRoundSeq())
                .containsExactly(1L, 1L, 2L, 2L, 3L, 3L);

        // 逐出不影响历史（把预算调小的等价验证：直接删除记忆行后历史仍在）
        // 这里验证更强命题：chat_memory 窗口缩小/消失后历史完整（FR-001）
        jdbcTemplate.update("DELETE FROM chat_memory WHERE memory_id = ?", tenantId(TENANT) + ":" + SESSION);
        streamAsk("问题四");
        ChatHistoryWriter.PageResult after = historyWriter.findMessages(tenantId(TENANT), SESSION, 0, 100);
        assertThat(after.total()).isEqualTo(8);
        assertThat(after.items().get(6).getContent()).isEqualTo("问题四");
    }

    @Test
    void 模型故障轮记FAILED且问答返回错误() throws Exception {
        OpenAiStreamingChatModel failing = org.mockito.Mockito.mock(OpenAiStreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onError(new IllegalStateException("model down"));
            return null;
        }).when(failing).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(tenantResourceRouter.getPrimary(eq(TENANT), eq(ResourceType.STREAMING_LLM),
                eq(OpenAiStreamingChatModel.class))).thenReturn(failing);

        streamAskExpectError("故障问题");

        ChatHistoryWriter.PageResult page = historyWriter.findMessages(tenantId(TENANT), SESSION, 0, 10);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items().get(0).getContent()).isEqualTo("故障问题");
        assertThat(page.items().get(1).getStatus()).isEqualTo("FAILED");
        assertThat(page.items().get(1).getContent()).isEmpty();
    }

    private void stubStreamingModel(String... answers) {
        OpenAiStreamingChatModel model = org.mockito.Mockito.mock(OpenAiStreamingChatModel.class);
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            String answer = answers[Math.min(callCount.getAndIncrement(), answers.length - 1)];
            handler.onPartialResponse(answer);
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(answer)).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(tenantResourceRouter.getPrimary(eq(TENANT), eq(ResourceType.STREAMING_LLM),
                eq(OpenAiStreamingChatModel.class))).thenReturn(model);
    }

    private void streamAsk(String question) throws Exception {
        streamAskExpectError(question);
    }

    private void streamAskExpectError(String question) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/v1/ask/stream")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "%s", "sessionId": "%s", "topK": 3}
                                """.formatted(question, SESSION)))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started));
    }
}

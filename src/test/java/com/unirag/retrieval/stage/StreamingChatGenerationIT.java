package com.unirag.retrieval.stage;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.QaService;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.retrieval.model.QaRequest;
import com.unirag.tenant.resource.ResourceType;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Foundational 验收（tasks.md T006）：ChatGenerationStage 感知 ChatTokenSink 后，
 * 流式模型逐段推送 delta、done 全文一致、记忆照常累积（FR-003/007/008）。
 *
 * <p>租户码全局唯一：{@code streamGen}。
 */
class StreamingChatGenerationIT extends AbstractRetrievalIT {

    private static final String TENANT = "streamGen";
    private static final String TABLE = "embeddings_streamgen_main";
    private static final String DOC = "stream-gen-doc";
    private static final String SESSION = "s-gen-1";

    @Autowired
    private QaService qaService;

    @Autowired
    private com.unirag.rag.route.TraditionalRagExecutor traditionalRagExecutor;

    private final List<String> deltas = new ArrayList<>();

    @BeforeEach
    void setUp() {
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

        // 确定性流式替身：依次回调两个 delta 后 onComplete（与真实模型行为对齐）
        OpenAiStreamingChatModel streamingModel = org.mockito.Mockito.mock(OpenAiStreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("甲片段");
            handler.onPartialResponse("乙片段");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("甲片段乙片段")).build());
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(tenantResourceRouter.getPrimary(eq(TENANT), eq(ResourceType.STREAMING_LLM),
                eq(OpenAiStreamingChatModel.class))).thenReturn(streamingModel);
        deltas.clear();
    }

    @Test
    void 流式生成逐段推送且全文与拼接一致并写入记忆() {
        // 直接驱动生成阶段：构造带 sink 的上下文，走完整问答编排
        com.unirag.retrieval.pipeline.StageContext ctx =
                new com.unirag.retrieval.pipeline.StageContext(
                        com.unirag.retrieval.pipeline.StageContext.newTraceId(), "QA", TENANT,
                        tenantId(TENANT), SESSION, "重排模型有哪些？", null);
        ctx.put(ChatTokenSink.CONTEXT_KEY, ChatTokenSink.of(deltas::add));

        com.unirag.retrieval.model.QaAnswer answer = inTenant(TENANT, () -> traditionalRagExecutor
                .execute(new com.unirag.rag.model.RagRequest("重排模型有哪些？", null, 5, 0.0, SESSION, null, null), ctx)
                .toQaAnswer());

        assertThat(deltas).containsExactly("甲片段", "乙片段");
        assertThat(answer.answer()).isEqualTo("甲片段乙片段");
        assertThat(answer.degraded()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(messages::jsonb) FROM chat_memory WHERE memory_id = ?",
                Integer.class, tenantId(TENANT) + ":" + SESSION))
                .isEqualTo(2);
    }
}

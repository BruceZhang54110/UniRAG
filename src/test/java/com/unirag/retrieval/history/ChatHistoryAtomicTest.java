package com.unirag.retrieval.history;

import com.unirag.retrieval.memory.ChatMemoryFactory;
import com.unirag.retrieval.memory.ChatMemoryProperties;
import com.unirag.retrieval.stage.ChatGenerationStage;
import com.unirag.retrieval.stage.ChatTokenSink;
import com.unirag.retrieval.stage.PromptBundle;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US1 补充验收（tasks.md T006，FR-001/005）：客户端中止轮以 ABORTED 状态入历史，
 * AI 内容为已生成部分；写入器在同事务插两行的原子性由 ChatHistoryWriterIT 配对断言
 * + 唯一键回滚保障（本测试经 mock writer 锁定调用契约：整轮一次调用、参数完整）。
 */
class ChatHistoryAtomicTest {

    @Test
    void 中止轮记ABORTED且AI内容为已生成部分() {
        TenantResourceRouter router = mock(TenantResourceRouter.class);
        ChatMemoryFactory memoryFactory = mock(ChatMemoryFactory.class);
        ChatMemoryProperties properties = mock(ChatMemoryProperties.class);
        ChatHistoryWriter historyWriter = mock(ChatHistoryWriter.class);
        when(memoryFactory.get(isNull(), isNull())).thenReturn(Optional.empty());
        when(properties.enabled()).thenReturn(true);

        OpenAiStreamingChatModel streamingModel = mock(OpenAiStreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("已生成部分");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("已生成部分")).build());
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(router.getPrimary(anyString(), eq(ResourceType.STREAMING_LLM),
                eq(OpenAiStreamingChatModel.class))).thenReturn(streamingModel);
        when(router.getPrimary(anyString(), eq(ResourceType.LLM), eq(OpenAiChatModel.class)))
                .thenThrow(new IllegalStateException("不应走同步路径"));

        ChatGenerationStage stage = new ChatGenerationStage(router, memoryFactory, properties, historyWriter);
        com.unirag.retrieval.pipeline.StageContext ctx = new com.unirag.retrieval.pipeline.StageContext(
                "trace-abort", "QA", "demo", 1L, "s-1", "中止场景问题", null);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ctx.put(ChatTokenSink.CONTEXT_KEY, new ChatTokenSink(delta -> cancelled.set(true), cancelled));

        stage.execute(new PromptBundle("资料[1]", List.of(chunk())), ctx);

        // 整轮一次调用：问题原文 + 已生成部分 + ABORTED + CLIENT_ABORTED + traceId
        verify(historyWriter).recordRound(eq(1L), eq("s-1"), eq("中止场景问题"),
                eq("已生成部分"), eq("ABORTED"), eq("CLIENT_ABORTED"), eq("trace-abort"), any());
    }

    private static com.unirag.retrieval.model.RetrievedChunk chunk() {
        return new com.unirag.retrieval.model.RetrievedChunk(1L, "doc", "main", null, 0, 0.9, "片段");
    }
}

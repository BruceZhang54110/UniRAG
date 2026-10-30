package com.unirag.retrieval.stage;

import com.unirag.retrieval.memory.ChatMemoryFactory;
import com.unirag.retrieval.memory.ChatMemoryProperties;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 中止语义验收（tasks.md T019，FR-008/R9）：客户端中止后生成阶段停止推送、
 * 该轮问答**不写入记忆**，结果以 CLIENT_ABORTED 降级透出。
 *
 * <p>纯单元测试（真实 PG 覆盖见 AskStreamEndpointIT 的记忆断言；本测试锁定中止分支语义）。
 */
class StreamingAbortTest {

    @Test
    void 客户端中止后停止推送且跳过记忆写回() {
        TenantResourceRouter router = mock(TenantResourceRouter.class);
        ChatMemoryFactory memoryFactory = mock(ChatMemoryFactory.class);
        dev.langchain4j.memory.ChatMemory memory = mock(dev.langchain4j.memory.ChatMemory.class);
        when(memory.messages()).thenReturn(List.of());
        when(memoryFactory.get(any(), any())).thenReturn(Optional.of(memory));
        ChatMemoryProperties properties = mock(ChatMemoryProperties.class);
        when(properties.enabled()).thenReturn(true);
        when(properties.recordNoContextRound()).thenReturn(true);

        OpenAiStreamingChatModel streamingModel = mock(OpenAiStreamingChatModel.class);
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("第一段");
            handler.onPartialResponse("第二段");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("第一段第二段")).build());
            return null;
        }).when(streamingModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(router.getPrimary(anyString(), eq(ResourceType.STREAMING_LLM),
                eq(OpenAiStreamingChatModel.class))).thenReturn(streamingModel);
        when(router.getPrimary(anyString(), eq(ResourceType.LLM), eq(OpenAiChatModel.class)))
                .thenThrow(new IllegalStateException("不应走同步路径"));

        ChatGenerationStage stage = new ChatGenerationStage(router, memoryFactory, properties);
        com.unirag.retrieval.pipeline.StageContext ctx = new com.unirag.retrieval.pipeline.StageContext(
                com.unirag.retrieval.pipeline.StageContext.newTraceId(), "QA", "demo", 1L,
                "s-abort", "问题原文", null);

        List<String> emitted = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ctx.put(ChatTokenSink.CONTEXT_KEY, new ChatTokenSink(delta -> {
            emitted.add(delta);
            cancelled.set(true);   // 模拟发送第一个增量后客户端断开
        }, cancelled));

        QaAnswer answer = stage.execute(
                new PromptBundle("资料[1]", List.of(chunk())), ctx);

        assertThat(emitted).containsExactly("第一段");
        assertThat(answer.degraded()).isTrue();
        assertThat(answer.degradeReason()).isEqualTo("CLIENT_ABORTED");
        assertThat(answer.answer()).isEqualTo("第一段第二段");
        // 关键：该轮不入记忆（FR-008）
        verify(memory, never()).add(any(ChatMessage.class));
    }

    private static com.unirag.retrieval.model.RetrievedChunk chunk() {
        return new com.unirag.retrieval.model.RetrievedChunk(1L, "doc", "main", null, 0, 0.9, "片段");
    }
}

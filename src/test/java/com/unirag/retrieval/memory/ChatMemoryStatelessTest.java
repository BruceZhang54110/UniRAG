package com.unirag.retrieval.memory;

import com.unirag.retrieval.stage.ChatGenerationStage;
import com.unirag.retrieval.stage.PromptBundle;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US1 无状态回归（tasks.md T008，FR-001）：不带 sessionId 时记忆工厂零交互，
 * LLM 输入只有 SystemMessage + UserMessage（与 015 前行为一致）。
 */
class ChatMemoryStatelessTest {

    @Test
    void 无sessionId时不读记忆且消息仅系统加问题() {
        TenantResourceRouter router = mock(TenantResourceRouter.class);
        ChatMemoryFactory factory = mock(ChatMemoryFactory.class);
        when(factory.get(isNull(), isNull())).thenReturn(Optional.empty());
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(router.getPrimary(anyString(), eq(ResourceType.LLM), eq(OpenAiChatModel.class))).thenReturn(model);
        List<ChatRequest> captured = new ArrayList<>();
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("回答")).build();
        });

        ChatMemoryProperties properties = mock(ChatMemoryProperties.class);
        ChatGenerationStage stage = new ChatGenerationStage(router, factory, properties);
        com.unirag.retrieval.pipeline.StageContext ctx = new com.unirag.retrieval.pipeline.StageContext(
                com.unirag.retrieval.pipeline.StageContext.newTraceId(), "QA", "demo", 1L,
                null, "问题", null);

        stage.execute(new PromptBundle("资料[1]", List.of(chunk())), ctx);

        assertThat(captured).hasSize(1);
        assertThat(new ArrayList<>(captured.get(0).messages())).containsExactlyElementsOf(List.of(
                SystemMessage.from(ChatGenerationStage.SYSTEM_PROMPT), UserMessage.from("资料[1]")));
        verify(factory, never()).get(anyLong(), anyString());
    }

    private static com.unirag.retrieval.model.RetrievedChunk chunk() {
        return new com.unirag.retrieval.model.RetrievedChunk(1L, "doc", "main", null, 0, 0.9, "片段");
    }

    @Test
    void 无命中时按配置决定是否写入记忆() {
        // recordNoContextRound=true（默认）：降级轮仍写记忆（FR-006）
        assertNoContextRemembered(true, 2);
        // recordNoContextRound=false：降级轮不写记忆
        assertNoContextRemembered(false, 0);
    }

    private void assertNoContextRemembered(boolean recordNoContext, int expectedAdds) {
        TenantResourceRouter router = mock(TenantResourceRouter.class);
        ChatMemoryFactory factory = mock(ChatMemoryFactory.class);
        dev.langchain4j.memory.ChatMemory memory = mock(dev.langchain4j.memory.ChatMemory.class);
        when(memory.messages()).thenReturn(List.of());
        when(factory.get(anyLong(), anyString())).thenReturn(Optional.of(memory));
        ChatMemoryProperties properties = mock(ChatMemoryProperties.class);
        when(properties.recordNoContextRound()).thenReturn(recordNoContext);
        ChatGenerationStage stage = new ChatGenerationStage(router, factory, properties);
        com.unirag.retrieval.pipeline.StageContext ctx = new com.unirag.retrieval.pipeline.StageContext(
                com.unirag.retrieval.pipeline.StageContext.newTraceId(), "QA", "demo", 1L,
                "s-1", "问题原文", null);

        // 检索为空：PromptBundle(null, []) → NO_CONTEXT 降级路径
        com.unirag.retrieval.model.QaAnswer answer =
                stage.execute(new PromptBundle(null, List.of()), ctx);

        assertThat(answer.degradeReason())
                .isEqualTo(com.unirag.retrieval.model.QaAnswer.NO_CONTEXT);
        verify(memory, org.mockito.Mockito.times(expectedAdds)).add(any(ChatMessage.class));
    }
}

package com.unirag.retrieval.query;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmQueryTransformerTest {

    private final ChatModel model = mock(ChatModel.class);

    private final LlmQueryTransformer transformer =
            new LlmQueryTransformer(() -> model, 200, 0.0, "primary-llm");

    @Test
    void 把原问题放入提示词并返回一条检索查询() {
        when(model.chat(any(ChatRequest.class))).thenReturn(response("文档切分配置"));

        Collection<Query> result = transformer.transform(Query.from("那个把文件切块的玩意儿怎么配？"));

        assertThat(result).hasSize(1);
        assertThat(result.iterator().next().text()).isEqualTo("文档切分配置");
        assertThat(transformer.modelSource()).isEqualTo("primary-llm");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        ChatRequest request = captor.getValue();
        assertThat(request.messages()).hasSize(2);
        assertThat(((SystemMessage) request.messages().get(0)).text()).contains("检索查询改写器");
        assertThat(((UserMessage) request.messages().get(1)).singleText())
                .contains("那个把文件切块的玩意儿怎么配？");
        assertThat(request.temperature()).isEqualTo(0.0);
        assertThat(request.maxOutputTokens()).isEqualTo(200);
    }

    @Test
    void 空响应返回空集合() {
        when(model.chat(any(ChatRequest.class))).thenReturn(null);

        assertThat(transformer.transform(Query.from("问题"))).isEmpty();
    }

    @Test
    void 空问题不调用模型() {
        assertThat(transformer.transform(null)).isEmpty();
    }

    @Test
    void 模型异常原样抛出() {
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> transformer.transform(Query.from("问题")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("down");
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}

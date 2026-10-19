package com.unirag.tenant.resource.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.retrieval.query.LlmQueryTransformer;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceConfig;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryRewriteModelResourceProviderTest {

    private static final String TENANT = "rewriteProvider";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TenantResourceRouter router = mock(TenantResourceRouter.class);

    private final QueryRewriteModelResourceProvider provider =
            new QueryRewriteModelResourceProvider(router);

    @Test
    void 空配置默认复用租户主语言模型() throws Exception {
        OpenAiChatModel llm = mock(OpenAiChatModel.class);
        when(llm.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("文档切分配置")).build());
        when(router.getPrimary(eq(TENANT), eq(ResourceType.LLM), eq(OpenAiChatModel.class)))
                .thenReturn(llm);

        QueryTransformer transformer = provider.build(config("{}"));

        assertThat(transformer).isInstanceOf(LlmQueryTransformer.class);
        assertThat(((LlmQueryTransformer) transformer).modelSource()).isEqualTo("primary-llm");
        Collection<Query> queries = transformer.transform(Query.from("原问题"));
        assertThat(queries).hasSize(1);
        assertThat(queries.iterator().next().text()).isEqualTo("文档切分配置");
    }

    @Test
    void 完整连接字段时使用专用模型() throws Exception {
        QueryTransformer transformer = provider.build(config("""
                {
                  "base-url": "https://api.example.com/v1",
                  "api-key": "tenant-secret",
                  "model-name": "small-model"
                }
                """));

        assertThat(transformer).isInstanceOf(LlmQueryTransformer.class);
        assertThat(((LlmQueryTransformer) transformer).modelSource()).isEqualTo("dedicated");
    }

    @Test
    void 连接字段部分提供时配置非法() throws Exception {
        assertThatThrownBy(() -> provider.build(config("""
                {"base-url": "https://api.example.com/v1"}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同时提供或同时省略");
    }

    @Test
    void primary_llm模式不允许配置timeout() throws Exception {
        assertThatThrownBy(() -> provider.build(config("""
                {"timeout": "PT1S"}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void 专用模型超时不得超过1点5秒() throws Exception {
        assertThatThrownBy(() -> provider.build(config("""
                {
                  "base-url": "https://api.example.com/v1",
                  "api-key": "tenant-secret",
                  "model-name": "small-model",
                  "timeout": "PT3S"
                }
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PT1.5S");
    }

    private static TenantResourceConfig config(String json) throws Exception {
        JsonNode node = OBJECT_MAPPER.readTree(json);
        return new TenantResourceConfig(1L, TENANT, ResourceType.QUERY_REWRITE_MODEL,
                "rewriteModel", true, node, true);
    }
}

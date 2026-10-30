package com.unirag.tenant.resource.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceConfig;
import com.unirag.tenant.resource.TenantResourceProvider;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalBoolean;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalDouble;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalDuration;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalInt;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.requiredText;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.requireObject;

/**
 * 流式 LLM 资源配置（016-sse-chat-streaming，research.md R2/R3）：JSON →
 * {@link OpenAiStreamingChatModel}。连接字段与 {@link LlmResourceProvider} 完全一致
 * （同一份配置语义，只是消费方式为流式回调）；租户未配置该类型时，流式端点降级为
 * 同步生成 + 单条缓冲事件（协议不变）。
 */
@Component
public class StreamingLlmResourceProvider implements TenantResourceProvider<OpenAiStreamingChatModel> {

    @Override
    public ResourceType resourceType() {
        return ResourceType.STREAMING_LLM;
    }

    @Override
    public Class<OpenAiStreamingChatModel> beanType() {
        return OpenAiStreamingChatModel.class;
    }

    @Override
    public OpenAiStreamingChatModel build(TenantResourceConfig config) {
        JsonNode c = requireObject(config);
        var builder = OpenAiStreamingChatModel.builder()
                .baseUrl(requiredText(c, "base-url"))
                .apiKey(requiredText(c, "api-key"))
                .modelName(requiredText(c, "model-name"));
        optionalDouble(c, "temperature", builder::temperature);
        optionalInt(c, "max-tokens", builder::maxTokens);
        optionalDuration(c, "timeout", builder::timeout);
        optionalBoolean(c, "log-requests", builder::logRequests);
        optionalBoolean(c, "log-responses", builder::logResponses);
        return builder.build();
    }
}

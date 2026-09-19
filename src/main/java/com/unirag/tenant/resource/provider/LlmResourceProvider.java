package com.unirag.tenant.resource.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceConfig;
import com.unirag.tenant.resource.TenantResourceProvider;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalBoolean;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalDouble;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalDuration;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalInt;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.requiredText;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.requireObject;

/**
 * LLM 资源配置：JSON → {@link OpenAiChatModel}（schema 见 contracts/resource-config-schemas.md）。
 */
@Component
public class LlmResourceProvider implements TenantResourceProvider<OpenAiChatModel> {

    @Override
    public ResourceType resourceType() {
        return ResourceType.LLM;
    }

    @Override
    public Class<OpenAiChatModel> beanType() {
        return OpenAiChatModel.class;
    }

    @Override
    public OpenAiChatModel build(TenantResourceConfig config) {
        JsonNode c = requireObject(config);
        var builder = OpenAiChatModel.builder()
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

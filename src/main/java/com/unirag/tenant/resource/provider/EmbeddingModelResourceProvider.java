package com.unirag.tenant.resource.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceConfig;
import com.unirag.tenant.resource.TenantResourceProvider;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;

import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalDuration;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalInt;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.requiredText;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.requireObject;

/**
 * 嵌入模型资源配置：JSON → {@link OpenAiEmbeddingModel}。
 */
@Component
public class EmbeddingModelResourceProvider implements TenantResourceProvider<OpenAiEmbeddingModel> {

    @Override
    public ResourceType resourceType() {
        return ResourceType.EMBEDDING_MODEL;
    }

    @Override
    public Class<OpenAiEmbeddingModel> beanType() {
        return OpenAiEmbeddingModel.class;
    }

    @Override
    public OpenAiEmbeddingModel build(TenantResourceConfig config) {
        JsonNode c = requireObject(config);
        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(requiredText(c, "base-url"))
                .apiKey(requiredText(c, "api-key"))
                .modelName(requiredText(c, "model-name"));
        optionalInt(c, "max-segments-per-batch", builder::maxSegmentsPerBatch);
        optionalDuration(c, "timeout", builder::timeout);
        return builder.build();
    }
}

package com.unirag.tenant.resource.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.unirag.retrieval.rerank.BgeRerankerScoringModel;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceConfig;
import com.unirag.tenant.resource.TenantResourceProvider;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalInt;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.requiredText;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.requireObject;

/**
 * 重排序模型资源配置（contracts/rerank-model.md §1）：JSON → {@link ScoringModel}。
 *
 * <p>首期只构造 {@link BgeRerankerScoringModel}；更换服务商协议时新增适配器，不改变 Provider 契约。
 */
@Component
public class RerankModelResourceProvider implements TenantResourceProvider<ScoringModel> {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);

    private static final int DEFAULT_MAX_DOCUMENTS_PER_BATCH = 32;

    @Override
    public ResourceType resourceType() {
        return ResourceType.RERANK_MODEL;
    }

    @Override
    public Class<ScoringModel> beanType() {
        return ScoringModel.class;
    }

    @Override
    public ScoringModel build(TenantResourceConfig config) {
        JsonNode c = requireObject(config);
        String baseUrl = requiredText(c, "base-url");
        String apiKey = requiredText(c, "api-key");
        String modelName = requiredText(c, "model-name");
        Duration timeout = timeout(c);
        int maxBatch = maxDocumentsPerBatch(c);
        return new BgeRerankerScoringModel(baseUrl, apiKey, modelName, timeout, maxBatch);
    }

    private static Duration timeout(JsonNode c) {
        JsonNode value = c.get("timeout");
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            return DEFAULT_TIMEOUT;
        }
        try {
            return Duration.parse(value.asText());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid config field: timeout");
        }
    }

    private static int maxDocumentsPerBatch(JsonNode c) {
        int[] value = {DEFAULT_MAX_DOCUMENTS_PER_BATCH};
        optionalInt(c, "max-documents-per-batch", v -> value[0] = v);
        if (value[0] < 1 || value[0] > 100) {
            throw new IllegalArgumentException("max-documents-per-batch 需在 1-100 之间");
        }
        return value[0];
    }
}

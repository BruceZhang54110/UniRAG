package com.unirag.tenant.resource.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.unirag.retrieval.query.LlmQueryTransformer;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceConfig;
import com.unirag.tenant.resource.TenantResourceProvider;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalDouble;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.optionalInt;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.requireObject;
import static com.unirag.tenant.resource.provider.ResourceConfigSupport.text;

/**
 * 查询改写资源配置（contracts/query-rewrite.md §1）：JSON → {@link QueryTransformer}。
 *
 * <p>连接字段三空时默认复用租户主语言模型（{@code primary-llm}）；三全时构建专用
 * {@link OpenAiChatModel}（{@code dedicated}）。资源的存在即租户级启用意图。
 */
@Component
public class QueryRewriteModelResourceProvider implements TenantResourceProvider<QueryTransformer> {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(1500);

    private static final int DEFAULT_MAX_TOKENS = 200;

    private static final double DEFAULT_TEMPERATURE = 0.0;

    private final TenantResourceRouter router;

    public QueryRewriteModelResourceProvider(TenantResourceRouter router) {
        this.router = router;
    }

    @Override
    public ResourceType resourceType() {
        return ResourceType.QUERY_REWRITE_MODEL;
    }

    @Override
    public Class<QueryTransformer> beanType() {
        return QueryTransformer.class;
    }

    @Override
    public QueryTransformer build(TenantResourceConfig config) {
        JsonNode c = requireObject(config);
        String baseUrl = text(c, "base-url");
        String apiKey = text(c, "api-key");
        String modelName = text(c, "model-name");
        int connectionFields = countPresent(baseUrl, apiKey, modelName);
        if (connectionFields != 0 && connectionFields != 3) {
            throw new IllegalArgumentException("base-url/api-key/model-name 需同时提供或同时省略");
        }

        double[] temperature = {DEFAULT_TEMPERATURE};
        optionalDouble(c, "temperature", value -> temperature[0] = value);
        int[] maxTokens = {DEFAULT_MAX_TOKENS};
        optionalInt(c, "max-tokens", value -> maxTokens[0] = value);
        validateParameters(temperature[0], maxTokens[0]);

        if (connectionFields == 0) {
            if (c.has("timeout") && !c.get("timeout").isNull()) {
                throw new IllegalArgumentException("timeout 仅在专用模型模式可配置");
            }
            String tenantCode = config.getTenantCode();
            return new LlmQueryTransformer(
                    () -> router.getPrimary(tenantCode, ResourceType.LLM, OpenAiChatModel.class),
                    maxTokens[0], temperature[0], "primary-llm");
        }

        validateBaseUrl(baseUrl);
        Duration timeout = timeout(c);
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(timeout)
                .temperature(temperature[0])
                .maxTokens(maxTokens[0])
                .build();
        return new LlmQueryTransformer(() -> model, maxTokens[0], temperature[0], "dedicated");
    }

    private static int countPresent(String... values) {
        int count = 0;
        for (String value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    private static void validateParameters(double temperature, int maxTokens) {
        if (!Double.isFinite(temperature) || temperature < 0.0 || temperature > 1.0) {
            throw new IllegalArgumentException("temperature 需在 0-1 之间");
        }
        if (maxTokens < 32 || maxTokens > 1000) {
            throw new IllegalArgumentException("max-tokens 需在 32-1000 之间");
        }
    }

    private static void validateBaseUrl(String baseUrl) {
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("base-url 需为 http/https URL");
        }
    }

    private static Duration timeout(JsonNode c) {
        JsonNode value = c.get("timeout");
        if (value == null || value.isNull()) {
            return DEFAULT_TIMEOUT;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("invalid config field: timeout");
        }
        Duration timeout;
        try {
            timeout = Duration.parse(value.asText());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid config field: timeout");
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(DEFAULT_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout 需大于 0 且不超过 PT1.5S");
        }
        return timeout;
    }
}

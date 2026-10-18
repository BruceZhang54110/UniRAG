package com.unirag.retrieval.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * BAAI/bge-reranker-v2-m3 的 {@link ScoringModel} 适配器（contracts/rerank-model.md §2）。
 *
 * <p>首期适配 Cohere/SiliconFlow 风格 {@code POST {base-url}/rerank}：请求 {@code documents}，
 * 响应 {@code results[].index / relevance_score}。服务商返回的 results 通常已排序，本类按
 * {@code index} 回填到输入顺序；缺失/越界/非有限分数一律抛异常，由上层 fail-open。
 */
public final class BgeRerankerScoringModel implements ScoringModel {

    private final RestClient http;
    private final String rerankUrl;
    private final String apiKey;
    private final String modelName;
    private final int maxDocumentsPerBatch;

    public BgeRerankerScoringModel(String baseUrl, String apiKey, String modelName,
                                   Duration timeout, int maxDocumentsPerBatch) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.rerankUrl = normalized + "/rerank";
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.maxDocumentsPerBatch = maxDocumentsPerBatch;
        this.http = RestClient.builder().requestFactory(requestFactory(timeout)).build();
    }

    /** 测试入口：注入已构建的 RestClient，避免真实网络。 */
    BgeRerankerScoringModel(RestClient http, String rerankUrl, String apiKey,
                            String modelName, int maxDocumentsPerBatch) {
        this.http = http;
        this.rerankUrl = rerankUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.maxDocumentsPerBatch = maxDocumentsPerBatch;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments.isEmpty()) {
            return Response.from(List.of());
        }
        List<Double> scores = new ArrayList<>(java.util.Collections.nCopies(segments.size(), Double.NaN));
        for (int start = 0; start < segments.size(); start += maxDocumentsPerBatch) {
            int end = Math.min(start + maxDocumentsPerBatch, segments.size());
            scoreBatch(segments.subList(start, end), query, scores, start);
        }
        for (Double score : scores) {
            if (score == null || !Double.isFinite(score)) {
                throw new IllegalStateException("rerank 返回不完整或非法分数");
            }
        }
        return Response.from(scores);
    }

    private void scoreBatch(List<TextSegment> batch, String query, List<Double> scores, int offset) {
        List<String> documents = batch.stream().map(TextSegment::text).toList();
        RerankResponse response = http.post()
                .uri(rerankUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RerankRequest(modelName, query, documents, documents.size()))
                .retrieve()
                .body(RerankResponse.class);
        if (response == null || response.results() == null) {
            throw new IllegalStateException("rerank 服务返回空响应");
        }
        boolean[] seen = new boolean[batch.size()];
        for (RerankResult result : response.results()) {
            if (result == null || result.index() == null || result.relevanceScore() == null) {
                throw new IllegalStateException("rerank 返回缺少 index/score");
            }
            int index = result.index();
            if (index < 0 || index >= batch.size()) {
                throw new IllegalStateException("rerank 返回越界 index");
            }
            if (!Double.isFinite(result.relevanceScore())) {
                throw new IllegalStateException("rerank 返回非有限分数");
            }
            scores.set(offset + index, result.relevanceScore());
            seen[index] = true;
        }
        for (boolean present : seen) {
            if (!present) {
                throw new IllegalStateException("rerank 返回分数不完整");
            }
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis()));
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankRequest(String model, String query, List<String> documents,
                                 @JsonProperty("top_n") int topN) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankResponse(List<RerankResult> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankResult(Integer index, @JsonProperty("relevance_score") Double relevanceScore) {
    }
}

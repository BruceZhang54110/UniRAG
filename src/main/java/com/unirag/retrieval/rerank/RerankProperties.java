package com.unirag.retrieval.rerank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 重排序配置（contracts/rerank-model.md §6）：全局开关、shadow、召回数与可选重排阈值。
 *
 * <p>是否真正启用还取决于租户是否配置了 {@code RERANK_MODEL}；本配置只提供全局/默认值。
 * {@code minScore} 默认 0.5，是 BGE reranker relevance_score 的首期保守起点，可按模型/语料校准。
 */
@ConfigurationProperties(prefix = "unirag.retrieval.rerank")
public record RerankProperties(@DefaultValue("true") boolean enabled,
                               @DefaultValue("false") boolean shadow,
                               @DefaultValue("20") int recallTopK,
                               @DefaultValue("0.5") Double minScore) {

    public RerankProperties {
        if (recallTopK < 1 || recallTopK > 50) {
            throw new IllegalArgumentException("unirag.retrieval.rerank.recall-top-k 需在 1-50 之间");
        }
        if (minScore != null && !Double.isFinite(minScore)) {
            throw new IllegalArgumentException("unirag.retrieval.rerank.min-score 需为有限数");
        }
    }
}

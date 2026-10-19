package com.unirag.retrieval.web;

import com.unirag.retrieval.model.RetrievalConstraints;
import com.unirag.retrieval.model.RetrievalRequest;

/**
 * 检索请求体（contracts/retrieval-api.md §2）：仅承载 HTTP 入参，省略的字段在此补默认值，
 * 取值约束由 {@link RetrievalRequest} 构造时统一校验。
 */
public record RetrieveRequest(String query, String granularity, Long documentId,
                              Integer topK, Double minScore, Boolean rerank, Boolean rewrite) {

    /** 兼容既有调用方：省略 rerank/rewrite。 */
    public RetrieveRequest(String query, String granularity, Long documentId, Integer topK, Double minScore) {
        this(query, granularity, documentId, topK, minScore, null, null);
    }

    /** 兼容 011 调用方：省略 rewrite。 */
    public RetrieveRequest(String query, String granularity, Long documentId, Integer topK, Double minScore,
                           Boolean rerank) {
        this(query, granularity, documentId, topK, minScore, rerank, null);
    }

    public RetrievalRequest toModel(double defaultMinScore) {
        int resolvedTopK = topK == null ? RetrievalConstraints.DEFAULT_TOP_K : topK;
        return new RetrievalRequest(query, granularity, documentId, resolvedTopK,
                minScore == null ? defaultMinScore : minScore, resolvedTopK,
                rerank == null || rerank, rewrite == null || rewrite);
    }
}

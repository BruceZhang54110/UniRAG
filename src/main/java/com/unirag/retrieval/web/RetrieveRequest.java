package com.unirag.retrieval.web;

import com.unirag.retrieval.model.RetrievalConstraints;
import com.unirag.retrieval.model.RetrievalRequest;

/**
 * 检索请求体（contracts/retrieval-api.md §2）：仅承载 HTTP 入参，省略的字段在此补默认值，
 * 取值约束由 {@link RetrievalRequest} 构造时统一校验。
 */
public record RetrieveRequest(String query, String granularity, Long documentId, Integer topK, Double minScore) {

    public RetrievalRequest toModel() {
        return new RetrievalRequest(query, granularity, documentId,
                topK == null ? RetrievalConstraints.DEFAULT_TOP_K : topK,
                minScore == null ? RetrievalConstraints.DEFAULT_MIN_SCORE : minScore);
    }
}

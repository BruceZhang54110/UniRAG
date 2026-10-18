package com.unirag.retrieval.web;

import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.model.RetrievalConstraints;

/**
 * 问答请求体（contracts/retrieval-api.md §3）：省略的字段在此补默认值，
 * 取值约束由 {@link QaRequest} 构造时统一校验。
 */
public record AskRequest(String question, String granularity, Integer topK, Double minScore, String sessionId) {

    public QaRequest toModel() {
        return new QaRequest(question, granularity,
                topK == null ? RetrievalConstraints.DEFAULT_TOP_K : topK,
                minScore == null ? RetrievalConstraints.DEFAULT_MIN_SCORE : minScore,
                sessionId);
    }
}

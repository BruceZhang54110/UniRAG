package com.unirag.retrieval.web;

import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.model.RetrievalConstraints;

/**
 * 问答请求体（contracts/retrieval-api.md §3）：省略的字段在此补默认值，
 * 取值约束由 {@link QaRequest} 构造时统一校验。
 */
public record AskRequest(String question, String granularity, Integer topK,
                         Double minScore, String sessionId, Boolean rerank, Boolean rewrite) {

    /** 兼容既有调用方：省略 rerank/rewrite。 */
    public AskRequest(String question, String granularity, Integer topK, Double minScore, String sessionId) {
        this(question, granularity, topK, minScore, sessionId, null, null);
    }

    /** 兼容 011 调用方：省略 rewrite。 */
    public AskRequest(String question, String granularity, Integer topK, Double minScore, String sessionId,
                      Boolean rerank) {
        this(question, granularity, topK, minScore, sessionId, rerank, null);
    }

    public QaRequest toModel(double defaultMinScore) {
        return new QaRequest(question, granularity,
                topK == null ? RetrievalConstraints.DEFAULT_TOP_K : topK,
                minScore == null ? defaultMinScore : minScore,
                sessionId, rerank, rewrite);
    }
}

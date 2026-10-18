package com.unirag.retrieval.model;

/**
 * 问答请求要素（data-model.md §5，contracts/retrieval-api.md §3）。
 *
 * @param sessionId 可选透传标识；本期不持久化对话记忆，仅回填进观测记录便于关联同会话调用
 * @param rerank    是否允许重排；{@code null} 表示按租户默认
 */
public record QaRequest(String question,
                        String granularity,
                        int topK,
                        double minScore,
                        String sessionId,
                        Boolean rerank) {

    /** 兼容既有调用方：由服务层按租户默认决定是否启用重排。 */
    public QaRequest(String question, String granularity, int topK, double minScore, String sessionId) {
        this(question, granularity, topK, minScore, sessionId, null);
    }

    public QaRequest {
        question = RetrievalConstraints.requireText(question, "question");
        granularity = RetrievalConstraints.normalizeGranularity(granularity);
        topK = RetrievalConstraints.requireTopK(topK);
        minScore = RetrievalConstraints.requireMinScore(minScore);
    }
}

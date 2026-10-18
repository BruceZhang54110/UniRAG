package com.unirag.retrieval.model;

/**
 * 检索请求要素（data-model.md §5，contracts/retrieval-api.md §2）。
 *
 * <p>构造即校验：非法取值在此收敛为 {@code VALIDATION_FAILED}，流水线内部只处理合法请求。
 *
 * @param query       查询文本
 * @param granularity 摄取管线 key 或向量存储资源 key；为空表示全部已启用管线
 * @param documentId  限定单个文档，可为空
 * @param topK        最终返回条数上限
 * @param minScore    向量相似度下界，取值范围 {@code [0,1]}
 * @param recallTopK  内部召回条数；启用重排时 &gt;= topK，未启用时等于 topK
 * @param rerank      本次是否实际启用重排（服务层解析租户资源后的结果）
 */
public record RetrievalRequest(String query,
                               String granularity,
                               Long documentId,
                               int topK,
                               double minScore,
                               int recallTopK,
                               boolean rerank) {

    /** 兼容既有调用方：不扩大召回、不启用重排。 */
    public RetrievalRequest(String query, String granularity, Long documentId, int topK, double minScore) {
        this(query, granularity, documentId, topK, minScore, topK, false);
    }

    public RetrievalRequest {
        query = RetrievalConstraints.requireText(query, "query");
        granularity = RetrievalConstraints.normalizeGranularity(granularity);
        topK = RetrievalConstraints.requireTopK(topK);
        minScore = RetrievalConstraints.requireMinScore(minScore);
        recallTopK = RetrievalConstraints.requireRecallTopK(topK, recallTopK);
    }

    public RetrievalRequest withRerank(boolean rerank, int recallTopK) {
        return new RetrievalRequest(query, granularity, documentId, topK, minScore, recallTopK, rerank);
    }
}

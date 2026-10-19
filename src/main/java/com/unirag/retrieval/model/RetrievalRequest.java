package com.unirag.retrieval.model;

/**
 * 检索请求要素（data-model.md §1.1，contracts/retrieval-api.md §2）。
 *
 * <p>构造即校验：非法取值在此收敛为 {@code VALIDATION_FAILED}，流水线内部只处理合法请求。
 * {@code query} 永远是用户原始问题；{@code retrievalQuery} 才是送入嵌入模型的文本，
 * 未改写时两者相同。
 *
 * @param query          用户原始问题
 * @param granularity    摄取管线 key 或向量存储资源 key；为空表示全部已启用管线
 * @param documentId     限定单个文档，可为空
 * @param topK           最终返回条数上限
 * @param minScore       向量相似度下界，取值范围 {@code [0,1]}
 * @param recallTopK     内部召回条数；启用重排时 &gt;= topK，未启用时等于 topK
 * @param rerank         本次是否实际启用重排（服务层解析租户资源后的结果）
 * @param rewrite        本次是否实际启用查询改写（服务层解析租户资源后的结果）
 * @param retrievalQuery 实际送入嵌入模型的检索文本；为空时取 {@code query}
 */
public record RetrievalRequest(String query,
                               String granularity,
                               Long documentId,
                               int topK,
                               double minScore,
                               int recallTopK,
                               boolean rerank,
                               boolean rewrite,
                               String retrievalQuery) {

    /** 兼容既有调用方：不扩大召回、不启用重排/改写。 */
    public RetrievalRequest(String query, String granularity, Long documentId, int topK, double minScore) {
        this(query, granularity, documentId, topK, minScore, topK, false, false, null);
    }

    /** 兼容 011 调用方：不启用改写，检索查询等于原问题。 */
    public RetrievalRequest(String query, String granularity, Long documentId, int topK, double minScore,
                            int recallTopK, boolean rerank) {
        this(query, granularity, documentId, topK, minScore, recallTopK, rerank, false, null);
    }

    /** 服务层使用：显式传入重排与改写意图，检索查询先等于原问题。 */
    public RetrievalRequest(String query, String granularity, Long documentId, int topK, double minScore,
                            int recallTopK, boolean rerank, boolean rewrite) {
        this(query, granularity, documentId, topK, minScore, recallTopK, rerank, rewrite, null);
    }

    public RetrievalRequest {
        query = RetrievalConstraints.requireText(query, "query");
        granularity = RetrievalConstraints.normalizeGranularity(granularity);
        topK = RetrievalConstraints.requireTopK(topK);
        minScore = RetrievalConstraints.requireMinScore(minScore);
        recallTopK = RetrievalConstraints.requireRecallTopK(topK, recallTopK);
        retrievalQuery = RetrievalConstraints.requireRetrievalQuery(retrievalQuery, query);
    }

    public RetrievalRequest withRerank(boolean rerank, int recallTopK) {
        return new RetrievalRequest(query, granularity, documentId, topK, minScore, recallTopK,
                rerank, rewrite, retrievalQuery);
    }

    public RetrievalRequest withRewrite(boolean rewrite) {
        return new RetrievalRequest(query, granularity, documentId, topK, minScore, recallTopK,
                rerank, rewrite, retrievalQuery);
    }

    public RetrievalRequest withRetrievalQuery(String retrievalQuery) {
        return new RetrievalRequest(query, granularity, documentId, topK, minScore, recallTopK,
                rerank, rewrite, retrievalQuery);
    }
}

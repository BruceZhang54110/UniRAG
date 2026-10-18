package com.unirag.retrieval.model;

/**
 * 检索请求要素（data-model.md §5，contracts/retrieval-api.md §2）。
 *
 * <p>构造即校验：非法取值在此收敛为 {@code VALIDATION_FAILED}，流水线内部只处理合法请求。
 *
 * @param query       查询文本
 * @param granularity 摄取管线 key 或向量存储资源 key；为空表示全部已启用管线
 * @param documentId  限定单个文档，可为空
 * @param topK        每张表返回条数上限（也是全局归并上限）
 * @param minScore    相似度下界，取值范围 {@code [0,1]}
 */
public record RetrievalRequest(String query, String granularity, Long documentId, int topK, double minScore) {

    public RetrievalRequest {
        query = RetrievalConstraints.requireText(query, "query");
        granularity = RetrievalConstraints.normalizeGranularity(granularity);
        topK = RetrievalConstraints.requireTopK(topK);
        minScore = RetrievalConstraints.requireMinScore(minScore);
    }
}

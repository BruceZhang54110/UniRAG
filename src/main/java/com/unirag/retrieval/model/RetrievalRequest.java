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
 * @param section        章节路径前缀限定（021）：匹配该章节及其子章节；为空表示不过滤
 */
public record RetrievalRequest(String query,
                               String granularity,
                               Long documentId,
                               int topK,
                               double minScore,
                               int recallTopK,
                               boolean rerank,
                               boolean rewrite,
                               String retrievalQuery,
                               String section) {

    /** 章节路径最大长度（与观测截断约定一致，data-model.md §6）。 */
    public static final int MAX_SECTION_LENGTH = 500;

    /** 兼容既有调用方：不扩大召回、不启用重排/改写、无章节限定。 */
    public RetrievalRequest(String query, String granularity, Long documentId, int topK, double minScore) {
        this(query, granularity, documentId, topK, minScore, topK, false, false, null, null);
    }

    /** 兼容 011 调用方：不启用改写，检索查询等于原问题。 */
    public RetrievalRequest(String query, String granularity, Long documentId, int topK, double minScore,
                            int recallTopK, boolean rerank) {
        this(query, granularity, documentId, topK, minScore, recallTopK, rerank, false, null, null);
    }

    /** 服务层使用：显式传入重排与改写意图，检索查询先等于原问题。 */
    public RetrievalRequest(String query, String granularity, Long documentId, int topK, double minScore,
                            int recallTopK, boolean rerank, boolean rewrite) {
        this(query, granularity, documentId, topK, minScore, recallTopK, rerank, rewrite, null, null);
    }

    public RetrievalRequest {
        query = RetrievalConstraints.requireText(query, "query");
        granularity = RetrievalConstraints.normalizeGranularity(granularity);
        topK = RetrievalConstraints.requireTopK(topK);
        minScore = RetrievalConstraints.requireMinScore(minScore);
        recallTopK = RetrievalConstraints.requireRecallTopK(topK, recallTopK);
        retrievalQuery = RetrievalConstraints.requireRetrievalQuery(retrievalQuery, query);
        section = normalizeSection(section);
    }

    public RetrievalRequest withRerank(boolean rerank, int recallTopK) {
        return new RetrievalRequest(query, granularity, documentId, topK, minScore, recallTopK,
                rerank, rewrite, retrievalQuery, section);
    }

    public RetrievalRequest withRewrite(boolean rewrite) {
        return new RetrievalRequest(query, granularity, documentId, topK, minScore, recallTopK,
                rerank, rewrite, retrievalQuery, section);
    }

    public RetrievalRequest withRetrievalQuery(String retrievalQuery) {
        return new RetrievalRequest(query, granularity, documentId, topK, minScore, recallTopK,
                rerank, rewrite, retrievalQuery, section);
    }

    /** 021：章节范围限定（null 清除过滤）。 */
    public RetrievalRequest withSection(String section) {
        return new RetrievalRequest(query, granularity, documentId, topK, minScore, recallTopK,
                rerank, rewrite, retrievalQuery, section);
    }

    /** 空/超长的章节限定在请求构造期收敛为校验失败（与既有约束一致）。 */
    private static String normalizeSection(String section) {
        if (section == null) {
            return null;
        }
        String normalized = section.strip();
        RetrievalConstraints.requireText(normalized, "section");
        if (normalized.length() > MAX_SECTION_LENGTH) {
            throw new IllegalArgumentException("section exceeds " + MAX_SECTION_LENGTH + " chars");
        }
        return normalized;
    }
}

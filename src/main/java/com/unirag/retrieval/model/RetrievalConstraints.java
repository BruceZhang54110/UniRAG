package com.unirag.retrieval.model;

import com.unirag.upload.support.ApiException;

/**
 * 检索请求要素的取值约束（contracts/retrieval-api.md §2/§3）：检索与问答两处请求共用，避免两份校验。
 */
public final class RetrievalConstraints {

    public static final int DEFAULT_TOP_K = 5;

    public static final int MAX_TOP_K = 50;

    public static final int MAX_TEXT_LENGTH = 2000;

    /** 改写后的检索查询上限；与原始问题不同长度才受此限制。 */
    public static final int MAX_RETRIEVAL_QUERY_LENGTH = 500;

    private RetrievalConstraints() {
    }

    /** 去空白后非空且不超长，超限给出可读原因（边界情况：空/超长查询）。 */
    public static String requireText(String text, String field) {
        String normalized = text == null ? null : text.strip();
        if (normalized == null || normalized.isEmpty()) {
            throw new ApiException("VALIDATION_FAILED", field + " 不能为空");
        }
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw new ApiException("VALIDATION_FAILED",
                    field + " 长度上限为 " + MAX_TEXT_LENGTH + " 字符");
        }
        return normalized;
    }

    public static int requireTopK(int topK) {
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new ApiException("VALIDATION_FAILED", "topK 需在 1-" + MAX_TOP_K + " 之间");
        }
        return topK;
    }

    /** 召回数必须不小于最终条数且不超过上限；只在内部构造时使用。 */
    public static int requireRecallTopK(int topK, int recallTopK) {
        if (recallTopK < topK || recallTopK > MAX_TOP_K) {
            throw new ApiException("VALIDATION_FAILED",
                    "recallTopK 需在 " + topK + "-" + MAX_TOP_K + " 之间");
        }
        return recallTopK;
    }

    /** {@code minScore} 不是余弦相似度本身：填 0.7 等价于余弦相似度 0.4（research.md R6/R7）。 */
    public static double requireMinScore(double minScore) {
        if (minScore < 0.0 || minScore > 1.0) {
            throw new ApiException("VALIDATION_FAILED", "minScore 需在 0-1 之间");
        }
        return minScore;
    }

    /**
     * 检索查询校验：空值/空白回退原始问题；与原问题不同时长度不得超过
     * {@link #MAX_RETRIEVAL_QUERY_LENGTH}。原始问题本身可保留其既有长度上限。
     */
    public static String requireRetrievalQuery(String retrievalQuery, String originalQuery) {
        if (retrievalQuery == null || retrievalQuery.isBlank()) {
            return originalQuery;
        }
        String normalized = retrievalQuery.strip();
        if (normalized.equals(originalQuery)) {
            return normalized;
        }
        if (normalized.length() > MAX_RETRIEVAL_QUERY_LENGTH) {
            throw new ApiException("VALIDATION_FAILED",
                    "retrievalQuery 长度上限为 " + MAX_RETRIEVAL_QUERY_LENGTH + " 字符");
        }
        return normalized;
    }

    /** 省略或空白视同未指定：检索该租户全部已启用管线的向量表（FR-003）。 */
    public static String normalizeGranularity(String granularity) {
        if (granularity == null || granularity.isBlank()) {
            return null;
        }
        return granularity.strip();
    }
}

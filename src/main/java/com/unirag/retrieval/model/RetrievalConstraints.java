package com.unirag.retrieval.model;

import com.unirag.upload.support.ApiException;

/**
 * 检索请求要素的取值约束（contracts/retrieval-api.md §2/§3）：检索与问答两处请求共用，避免两份校验。
 */
public final class RetrievalConstraints {

    public static final int DEFAULT_TOP_K = 5;

    public static final int MAX_TOP_K = 50;

    /** 分数语义为 {@code (1 + cosine_similarity) / 2}，取值范围 {@code [0,1]}（research.md R6）。 */
    public static final double DEFAULT_MIN_SCORE = 0.0;

    public static final int MAX_TEXT_LENGTH = 2000;

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

    /** {@code minScore} 不是余弦相似度本身：填 0.7 等价于余弦相似度 0.4（research.md R6/R7）。 */
    public static double requireMinScore(double minScore) {
        if (minScore < 0.0 || minScore > 1.0) {
            throw new ApiException("VALIDATION_FAILED", "minScore 需在 0-1 之间");
        }
        return minScore;
    }

    /** 省略或空白视同未指定：检索该租户全部已启用管线的向量表（FR-003）。 */
    public static String normalizeGranularity(String granularity) {
        if (granularity == null || granularity.isBlank()) {
            return null;
        }
        return granularity.strip();
    }
}

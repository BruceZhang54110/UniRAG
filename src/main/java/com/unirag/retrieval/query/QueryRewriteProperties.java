package com.unirag.retrieval.query;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 查询改写配置（contracts/query-rewrite.md §6）：全局开关、观察模式与输出约束。
 *
 * <p>是否真正启用还取决于租户是否配置了 {@code QUERY_REWRITE_MODEL}；本配置只提供全局策略。
 */
@ConfigurationProperties(prefix = "unirag.retrieval.query-rewrite")
public record QueryRewriteProperties(@DefaultValue("true") boolean enabled,
                                     @DefaultValue("false") boolean shadow,
                                     @DefaultValue("500") int maxOutputChars,
                                     @DefaultValue("2.0") double maxOutputRatio,
                                     @DefaultValue("2000") int maxInputChars,
                                     @DefaultValue("120") int previewChars) {

    public QueryRewriteProperties {
        if (maxOutputChars < 1 || maxOutputChars > 2000) {
            throw new IllegalArgumentException("unirag.retrieval.query-rewrite.max-output-chars 需在 1-2000 之间");
        }
        if (!Double.isFinite(maxOutputRatio) || maxOutputRatio < 1.0 || maxOutputRatio > 10.0) {
            throw new IllegalArgumentException("unirag.retrieval.query-rewrite.max-output-ratio 需在 1.0-10.0 之间");
        }
        if (maxInputChars < 1 || maxInputChars > 2000) {
            throw new IllegalArgumentException("unirag.retrieval.query-rewrite.max-input-chars 需在 1-2000 之间");
        }
        if (previewChars < 16 || previewChars > 500) {
            throw new IllegalArgumentException("unirag.retrieval.query-rewrite.preview-chars 需在 16-500 之间");
        }
    }
}

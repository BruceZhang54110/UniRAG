package com.unirag.retrieval.query;

import com.unirag.retrieval.pipeline.StageStatus;

/**
 * 一次查询改写的摘要（data-model.md §1.4）：由 {@link QueryRewriteEnhancement} 写入
 * {@code StageContext.attributes}，供 {@code QueryRewriteObservationExtension} 落观测行。
 *
 * @param modelKey       本次解析到的 QUERY_REWRITE_MODEL 资源 key；解析失败时为空
 * @param modelSource    {@code primary-llm} 或 {@code dedicated}；无法识别时为空
 * @param originalChars  原始问题字符数
 * @param retrievalChars 检索查询字符数；回退时等于 originalChars
 * @param detail         仅含 shadow、模型来源、长度与脱敏短摘要
 */
public record QueryRewriteOutcome(String modelKey,
                                  String modelSource,
                                  int originalChars,
                                  int retrievalChars,
                                  long durationMs,
                                  StageStatus status,
                                  String reason,
                                  boolean shadow,
                                  String detail) {

    public static final String CONTEXT_KEY = "query.rewrite.outcome";
}

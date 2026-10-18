package com.unirag.retrieval.model;

import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageStatus;

/**
 * 阶段结果（data-model.md §5）：一次调用中单个阶段的观测载荷。
 *
 * <p>与 {@code StageContext} 合并后落成 {@code retrieval_observation} 的一行；跨阶段共享的
 * 链路标识/租户/会话/查询摘要等字段由上下文提供，此处只带阶段自身的结果。
 */
public record StageResult(StageName stage,
                          String targetKey,
                          StageStatus status,
                          Long durationMs,
                          Integer hitCount,
                          Double topScore,
                          Integer itemCount,
                          String errorMessage) {

    /** 检索调用（data-model.md §1 的 kind 列）。 */
    public static final String KIND_RETRIEVAL = "RETRIEVAL";

    /** 问答调用。 */
    public static final String KIND_QA = "QA";
}

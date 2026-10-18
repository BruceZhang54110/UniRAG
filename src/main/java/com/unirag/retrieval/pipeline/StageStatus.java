package com.unirag.retrieval.pipeline;

/**
 * 阶段结果状态（data-model.md §1 的 {@code status} 列）。
 *
 * <p>{@link #DEGRADED} 仅用于"整体仍返回但已降级"：问答检索为空，或检索时部分表失败。
 */
public enum StageStatus {

    SUCCESS,

    FAILED,

    DEGRADED
}

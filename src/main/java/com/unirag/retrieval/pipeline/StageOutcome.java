package com.unirag.retrieval.pipeline;

/**
 * 阶段执行结果（contracts/pipeline-extension.md §3.1）：观察型扩展在阶段结束后据此落记录。
 *
 * @param status       阶段状态
 * @param durationMs   阶段耗时
 * @param errorMessage 失败/降级原因摘要，成功时为 {@code null}
 */
public record StageOutcome(StageStatus status, long durationMs, String errorMessage) {

    public static StageOutcome success(long durationMs) {
        return new StageOutcome(StageStatus.SUCCESS, durationMs, null);
    }

    public static StageOutcome degraded(long durationMs, String errorMessage) {
        return new StageOutcome(StageStatus.DEGRADED, durationMs, errorMessage);
    }

    public static StageOutcome failed(long durationMs, String errorMessage) {
        return new StageOutcome(StageStatus.FAILED, durationMs, errorMessage);
    }
}

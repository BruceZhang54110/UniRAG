package com.unirag.retrieval.pipeline;

/**
 * {@link FailurePolicy#FAIL_CLOSED} 的扩展失败：该阶段被视为失败（contracts/pipeline-extension.md §4）。
 *
 * <p>调用方（如问答编排）可捕获它并进入降级语义；未被捕获时由统一异常处理器收敛为 500。
 */
public class StageFailureException extends RuntimeException {

    private final StageName stage;

    public StageFailureException(StageName stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
    }

    public StageName stage() {
        return stage;
    }
}

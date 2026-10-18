package com.unirag.retrieval.pipeline;

/**
 * 扩展失败语义（contracts/pipeline-extension.md §4）：
 * {@link #FAIL_OPEN} 记录并跳过该扩展，主链路继续；{@link #FAIL_CLOSED} 该阶段视为失败并进入降级。
 */
public enum FailurePolicy {

    FAIL_OPEN,

    FAIL_CLOSED
}

package com.unirag.retrieval.pipeline;

import java.util.Set;

/**
 * 观察型扩展（FR-012，contracts/pipeline-extension.md §3.1）：只读、数量不限、顺序无关。
 *
 * <p>承载日志、追踪、指标、审计。所有观察型回调在阶段执行前后触发，看到的永远是阶段的
 * 最终输入与最终输出（含增强型改写之后），因此其数据语义不因增强型扩展的有无而改变。
 */
public interface ObservationExtension {

    /** 该阶段开始前的回调；MUST NOT 修改 input。 */
    void beforeStage(StageName stage, Object input, StageContext ctx);

    /** 该阶段结束（成功或失败）后的回调；MUST NOT 修改 output。 */
    void afterStage(StageName stage, Object output, StageOutcome outcome, StageContext ctx);

    /** 关心的阶段；空集表示全部阶段。 */
    default Set<StageName> stages() {
        return Set.of();
    }

    /** 观察型失败绝不该让用户查不到答案，故默认 {@link FailurePolicy#FAIL_OPEN}（§4）。 */
    default FailurePolicy failurePolicy() {
        return FailurePolicy.FAIL_OPEN;
    }
}

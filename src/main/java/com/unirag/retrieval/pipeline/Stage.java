package com.unirag.retrieval.pipeline;

/**
 * 流水线阶段（contracts/pipeline-extension.md §2）。
 *
 * <p>不变量：阶段实现是纯编排——外部依赖一律经 {@code TenantResourceRouter} 按租户解析；
 * 不处理日志/指标/追踪等横切关注点（那是扩展点的职责）；单次调用内无状态。
 *
 * @param <I> 阶段输入类型
 * @param <O> 阶段输出类型
 */
public interface Stage<I, O> {

    /** 用于观测记录与扩展点过滤。 */
    StageName name();

    O execute(I input, StageContext ctx);
}

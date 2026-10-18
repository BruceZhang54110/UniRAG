package com.unirag.retrieval.pipeline;

import java.util.Set;

/**
 * 增强型扩展（FR-012，contracts/pipeline-extension.md §3.2）：可改写阶段输入/输出并可短路。
 *
 * <p>承载重排、过滤、提示词增强等能力。须显式排序、数量受控；同一阶段上多个扩展按
 * {@link #order()} 升序执行，重复时以 Bean 名兜底，保证同输入同输出。
 */
public interface EnhancementExtension {

    /** 顺序，值小者先执行；MUST 显式声明。 */
    int order();

    /** 可改写阶段输入；返回新的输入。 */
    Object beforeStage(StageName stage, Object input, StageContext ctx);

    /** 可改写阶段输出，或抛出 {@link ShortCircuit} 短路；返回新的输出。 */
    Object afterStage(StageName stage, Object output, StageContext ctx);

    /** 关心的阶段；空集表示全部阶段。 */
    default Set<StageName> stages() {
        return Set.of();
    }

    /** 默认按性质取：可选优化 FAIL_OPEN，影响正确性者 FAIL_CLOSED（§4）。 */
    FailurePolicy failurePolicy();
}

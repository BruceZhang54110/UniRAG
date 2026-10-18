package com.unirag.retrieval.pipeline;

/**
 * 增强型扩展的短路信号（contracts/pipeline-extension.md §3.2）：携带替代输出终止后续阶段。
 *
 * <p>不作为失败处理——流水线视其为该阶段正常完成，并按携带值作为整条流水线的结果。
 */
public class ShortCircuit extends RuntimeException {

    private final transient Object output;

    public ShortCircuit(Object output) {
        super("extension short-circuited the pipeline");
        this.output = output;
    }

    public Object output() {
        return output;
    }
}

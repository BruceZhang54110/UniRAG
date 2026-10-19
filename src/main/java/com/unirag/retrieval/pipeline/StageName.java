package com.unirag.retrieval.pipeline;

/**
 * 流水线阶段标识（contracts/pipeline-extension.md §1）：检索执行前三段，问答执行全部五段。
 *
 * <p>同时作为观测记录的 {@code stage} 取值来源（research.md R3）。{@link #QUERY_REWRITE}
 * 仅用于观测：实际执行由 {@code QUERY_EMBEDDING} 的增强型扩展承担。
 */
public enum StageName {

    /** 查询改写；以 QUERY_EMBEDDING 的增强型扩展执行，观测上独立成行。 */
    QUERY_REWRITE,

    /** 查询向量化。 */
    QUERY_EMBEDDING,

    /** 逐表相似度检索。 */
    VECTOR_SEARCH,

    /** 跨表确定性归并 + topK。 */
    MERGE,

    /** 重排序；以 MERGE 的增强型扩展执行，观测上独立成行。 */
    RERANK,

    /** 提示词组装。 */
    PROMPT_ASSEMBLY,

    /** 聊天模型调用。 */
    CHAT_GENERATION
}

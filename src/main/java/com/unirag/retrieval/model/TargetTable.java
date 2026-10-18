package com.unirag.retrieval.model;

/**
 * 本次检索的目标向量表（由租户已启用摄取管线解析得到，FR-003）。
 *
 * @param pipelineKey 摄取管线 key，即调用方视角的"粒度"
 * @param storeKey    向量存储资源 key（经 Router 解析 EMBEDDING_STORE）
 * @param modelKey    该管线的嵌入模型资源 key（同一租户不同管线可用不同模型）
 */
public record TargetTable(String pipelineKey, String storeKey, String modelKey) {
}

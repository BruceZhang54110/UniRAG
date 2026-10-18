package com.unirag.retrieval.stage;

import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.TargetTable;
import dev.langchain4j.data.embedding.Embedding;

import java.util.List;
import java.util.Map;

/**
 * 查询向量化阶段的输出：解析出的目标表 + 按嵌入模型资源 key 索引的查询向量。
 *
 * <p>同一租户的不同管线可配不同嵌入模型（例如粗/细粒度维度不同），故按模型分别向量化，
 * 逐表检索时取自己那一份，避免维度不匹配被切成单表故障。
 */
public record QueryVector(RetrievalRequest request, List<TargetTable> targets, Map<String, Embedding> embeddings) {
}

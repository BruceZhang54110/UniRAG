package com.unirag.retrieval.stage;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.List;

/**
 * 单张向量表的检索命中（按 {@code score} 降序，由向量库直接保证）。
 */
public record TableHits(String storeKey, String pipelineKey, List<EmbeddingMatch<TextSegment>> matches) {
}

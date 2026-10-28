package com.unirag.retrieval.web;

import com.unirag.retrieval.model.RetrievedChunk;

/**
 * 检索命中的片段（contracts/retrieval-api.md §2 的 {@code chunks} 元素）。
 */
public record ChunkView(long documentId,
                        String docName,
                        String pipelineKey,
                        Long documentVersionId,
                        int chunkIndex,
                        double score,
                        Double rerankScore,
                        String text) {

    public static ChunkView of(RetrievedChunk chunk) {
        return new ChunkView(chunk.documentId(), chunk.docName(), chunk.pipelineKey(),
                chunk.documentVersionId(), chunk.chunkIndex(), chunk.score(),
                chunk.rerankScore(), chunk.text());
    }
}

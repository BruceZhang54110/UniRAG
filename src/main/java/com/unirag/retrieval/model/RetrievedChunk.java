package com.unirag.retrieval.model;

/**
 * 检索输出单元（data-model.md §5）：一条带来源元数据的命中片段。
 *
 * @param documentVersionId 异步摄取路径才写入的版本标识，可为空
 */
public record RetrievedChunk(long documentId,
                             String docName,
                             String pipelineKey,
                             Long documentVersionId,
                             int chunkIndex,
                             double score,
                             String text) {
}

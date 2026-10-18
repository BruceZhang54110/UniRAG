package com.unirag.retrieval.model;

/**
 * 检索输出单元（data-model.md §5）：一条带来源元数据的命中片段。
 *
 * @param documentVersionId 异步摄取路径才写入的版本标识，可为空
 * @param rerankScore       重排相关性分数；未启用重排时为 {@code null}
 */
public record RetrievedChunk(long documentId,
                             String docName,
                             String pipelineKey,
                             Long documentVersionId,
                             int chunkIndex,
                             double score,
                             String text,
                             Double rerankScore) {

    /** 兼容既有调用方：未重排时 {@code rerankScore=null}。 */
    public RetrievedChunk(long documentId, String docName, String pipelineKey, Long documentVersionId,
                          int chunkIndex, double score, String text) {
        this(documentId, docName, pipelineKey, documentVersionId, chunkIndex, score, text, null);
    }

    public RetrievedChunk withRerankScore(double rerankScore) {
        return new RetrievedChunk(documentId, docName, pipelineKey, documentVersionId,
                chunkIndex, score, text, rerankScore);
    }
}

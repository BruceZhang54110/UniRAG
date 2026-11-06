package com.unirag.retrieval.model;

/**
 * 检索输出单元（data-model.md §5）：一条带来源元数据的命中片段。
 *
 * @param documentVersionId 异步摄取路径才写入的版本标识，可为空
 * @param rerankScore       重排相关性分数；未启用重排时为 {@code null}
 * @param sectionPath       结构感知策略的章节路径（021）；未启用或前言时为 {@code null}/空
 */
public record RetrievedChunk(long documentId,
                             String docName,
                             String pipelineKey,
                             Long documentVersionId,
                             int chunkIndex,
                             double score,
                             String text,
                             Double rerankScore,
                             String sectionPath) {

    /** 兼容既有调用方：未重排且无章节路径。 */
    public RetrievedChunk(long documentId, String docName, String pipelineKey, Long documentVersionId,
                          int chunkIndex, double score, String text) {
        this(documentId, docName, pipelineKey, documentVersionId, chunkIndex, score, text, null, null);
    }

    /** 兼容 020 调用方：重排分数可空、无章节路径。 */
    public RetrievedChunk(long documentId, String docName, String pipelineKey, Long documentVersionId,
                          int chunkIndex, double score, String text, Double rerankScore) {
        this(documentId, docName, pipelineKey, documentVersionId, chunkIndex, score, text, rerankScore, null);
    }

    public RetrievedChunk withRerankScore(double rerankScore) {
        return new RetrievedChunk(documentId, docName, pipelineKey, documentVersionId,
                chunkIndex, score, text, rerankScore, sectionPath);
    }

    /** 021：回填父块命中时继承子块的章节路径。 */
    public RetrievedChunk withSectionPath(String sectionPath) {
        return new RetrievedChunk(documentId, docName, pipelineKey, documentVersionId,
                chunkIndex, score, text, rerankScore, sectionPath);
    }
}

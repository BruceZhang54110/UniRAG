package com.unirag.ingestion;

import com.unirag.infrastructure.mp.entity.IngestionPipeline;

/**
 * 单条管线的执行结果（contracts §1 IngestOutcome.runs 元素）。
 *
 * @param pipelineKey        管线 key
 * @param status             SUCCESS / FAILED
 * @param chunkCount         本管线产出片段数（FAILED 时为 0）
 * @param errorMessage       失败原因（截断 500 字符，不含密钥）；SUCCESS 时为 null
 * @param embeddingStoreKey  实际使用的租户向量存储资源 key
 * @param embeddingModelKey  实际使用的租户嵌入模型资源 key
 */
public record RunOutcome(String pipelineKey, RunStatus status, int chunkCount, String errorMessage,
                         String embeddingStoreKey, String embeddingModelKey) {

    static RunOutcome success(IngestionPipeline pipeline, int chunkCount) {
        return new RunOutcome(pipeline.getPipelineKey(), RunStatus.SUCCESS, chunkCount, null,
                pipeline.getEmbeddingStoreKey(), pipeline.getEmbeddingModelKey());
    }

    static RunOutcome failed(IngestionPipeline pipeline, String errorMessage) {
        return new RunOutcome(pipeline.getPipelineKey(), RunStatus.FAILED, 0, errorMessage,
                pipeline.getEmbeddingStoreKey(), pipeline.getEmbeddingModelKey());
    }

    static RunOutcome noop(IngestionPipeline pipeline) {
        return success(pipeline, 0);
    }
}

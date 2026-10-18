package com.unirag.retrieval.rerank;

import com.unirag.retrieval.pipeline.StageStatus;

/**
 * 一次重排的结果摘要（data-model.md §1.4）：由 {@link RerankEnhancement} 写入
 * {@code StageContext.attributes}，供 {@code RerankObservationExtension} 落观测行。
 *
 * @param detail 紧凑候选明细，仅含 documentId/pipelineKey/chunkIndex、分数与 keep/drop
 */
public record RerankOutcome(int candidates,
                            int kept,
                            Double topScore,
                            long durationMs,
                            StageStatus status,
                            String reason,
                            boolean shadow,
                            String detail) {

    public static final String CONTEXT_KEY = "rerank.outcome";
}

package com.unirag.retrieval.model;

import com.unirag.retrieval.pipeline.StageStatus;

/**
 * 一次父块回填的结果摘要（020 data-model.md §5）：由 {@code ParentFetchStage} 写入
 * {@code StageContext.attributes}，供 {@code ParentFetchObservationExtension} 落观测行。
 *
 * @param detail 紧凑父块明细，仅含 documentId/pipelineKey/parentSeq 与 keep/drop，禁含文本原文（原则 V）
 */
public record ParentFetchOutcome(int fetchedParents,
                                 int mergedFromChildren,
                                 int degradedToChild,
                                 int truncatedByBudget,
                                 long durationMs,
                                 StageStatus status,
                                 String reason,
                                 String detail) {

    public static final String CONTEXT_KEY = "parentFetch.outcome";
}

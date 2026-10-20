package com.unirag.eval.metric;

/** 失败归因（013 data-model §2.3）。 */
public enum FailureCategory {
    CHUNKING_GAP,
    NOT_RECALLED,
    MERGE_OR_TOPK_DROPPED,
    RERANK_DROPPED,
    THRESHOLD_FILTERED,
    BELOW_TOPK,
    LABEL_INVALID,
    UNANSWERABLE_FALSE_POSITIVE
}

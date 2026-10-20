package com.unirag.eval.dataset;

/** 单条摄取管线配置（013 data-model §1.5 PipelineConfig）。 */
public record PipelineConfig(String key,
                             int maxSegmentTokens,
                             int overlapTokens,
                             String modelKey,
                             String storeKey) {
}

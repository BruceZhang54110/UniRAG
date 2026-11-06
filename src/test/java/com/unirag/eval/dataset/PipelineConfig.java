package com.unirag.eval.dataset;

/** 单条摄取管线配置（013 data-model §1.5 PipelineConfig；020 扩展父子策略）。 */
public record PipelineConfig(String key,
                             int maxSegmentTokens,
                             int overlapTokens,
                             String modelKey,
                             String storeKey,
                             String strategy,
                             Integer parentMaxTokens) {

    public PipelineConfig {
        strategy = strategy == null || strategy.isBlank() ? "RECURSIVE" : strategy.trim();
    }

    /** 兼容既有配置：单级递归策略（013 调用方）。 */
    public PipelineConfig(String key, int maxSegmentTokens, int overlapTokens, String modelKey, String storeKey) {
        this(key, maxSegmentTokens, overlapTokens, modelKey, storeKey, "RECURSIVE", null);
    }
}

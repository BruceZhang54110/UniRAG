package com.unirag.eval.dataset;

/** 生产遍检索配置（013 data-model §1.5 RetrievalConfig）。 */
public record RetrievalConfig(int topK,
                              int recallTopK,
                              double minScore,
                              boolean rerank,
                              boolean rewrite) {
}

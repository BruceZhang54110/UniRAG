package com.unirag.ingestion;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.TokenCountEstimator;

/**
 * 切片策略映射（research.md R3）：管线 {@code strategy} 字段 → {@link DocumentSplitter} 实现。
 *
 * <p>v1 仅 {@code RECURSIVE}（递归层级切分：段落→句子→词，上限/重叠以 token 计量）；
 * 升级路径 = 新增策略枚举 + 本工厂分支，不动主流程。未知策略抛 {@link IllegalArgumentException}。
 */
public final class DocumentSplitterFactory {

    /** 递归切分策略标识（ingestion_pipeline.strategy 取值）。 */
    public static final String STRATEGY_RECURSIVE = "RECURSIVE";

    private DocumentSplitterFactory() {
    }

    /**
     * 按策略创建切片器。
     *
     * @param strategy        策略标识（大小写不敏感，v1 仅 RECURSIVE）
     * @param maxTokens       片段 token 上限
     * @param overlapTokens   相邻片段重叠 token 数
     * @param tokenizer       token 计量器
     */
    public static DocumentSplitter create(String strategy, int maxTokens, int overlapTokens,
                                          TokenCountEstimator tokenizer) {
        if (strategy == null || STRATEGY_RECURSIVE.equalsIgnoreCase(strategy.trim())) {
            return DocumentSplitters.recursive(maxTokens, overlapTokens, tokenizer);
        }
        throw new IllegalArgumentException("Unknown document split strategy: " + strategy
                + " (supported: " + STRATEGY_RECURSIVE + ")");
    }
}

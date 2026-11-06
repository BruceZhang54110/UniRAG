package com.unirag.ingestion;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.TokenCountEstimator;

/**
 * 切片策略映射（research.md R3；020-parent-child-chunking）：管线 {@code strategy} 字段 →
 * {@link DocumentSplitter} 实现。
 *
 * <p>v1 起 {@code RECURSIVE}（递归层级切分：段落→句子→词，上限/重叠以 token 计量）；
 * 020 新增 {@code PARENT_CHILD}（父子两级切分，见 {@link #createParentChild}）。
 * 升级路径 = 新增策略枚举 + 本工厂分支，不动主流程。未知策略抛 {@link IllegalArgumentException}。
 */
public final class DocumentSplitterFactory {

    /** 递归切分策略标识（ingestion_pipeline.strategy 取值）。 */
    public static final String STRATEGY_RECURSIVE = "RECURSIVE";

    /** 父子两级切分策略标识（020-parent-child-chunking）。 */
    public static final String STRATEGY_PARENT_CHILD = "PARENT_CHILD";

    /** 结构感知切分策略标识（021-structure-aware-chunking）。 */
    public static final String STRATEGY_STRUCTURE_AWARE = "STRUCTURE_AWARE";

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
                + " (supported: " + STRATEGY_RECURSIVE + ", " + STRATEGY_PARENT_CHILD + ")");
    }

    /**
     * 父子两级切分器（020-parent-child-chunking）：父块仅落库，子块入向量表。
     *
     * @param maxChildTokens  子块 token 上限（管线 max_segment_tokens 在父子策略下的语义）
     * @param overlapTokens   子块重叠 token 数
     * @param parentMaxTokens 父块 token 上限；必须大于子块上限，否则抛 {@link IllegalArgumentException}
     *                        （由 PipelineExecutor 收敛为 FAILED run）
     */
    public static ParentChildSplitter createParentChild(int maxChildTokens, int overlapTokens,
                                                        int parentMaxTokens, TokenCountEstimator tokenizer) {
        return new ParentChildSplitter(maxChildTokens, overlapTokens, parentMaxTokens, tokenizer);
    }
}

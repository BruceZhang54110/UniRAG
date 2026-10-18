package com.unirag.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 检索默认参数（contracts/retrieval-api.md §2/§3）：调用方省略 {@code minScore} 时使用。
 *
 * <p>默认值 {@code 0.70} 由 demo 租户（BAAI/bge-m3）的 46 次真实检索校准：不相关查询的
 * 最高 topScore 为 0.6958，相关查询的最低 topScore 为 0.7075，0.70 落在两者之间；分数语义为
 * {@code (1 + cosine_similarity) / 2}，0.70 约等于余弦相似度 0.40。不同嵌入模型的分布差异较大，
 * 部署时可用 {@code UNIRAG_DEFAULT_MIN_SCORE} 覆盖；集成测试的确定性嵌入模型另覆盖为 0.0。
 */
@ConfigurationProperties(prefix = "unirag.retrieval")
public record RetrievalProperties(@DefaultValue("0.70") double defaultMinScore) {

    public RetrievalProperties {
        if (defaultMinScore < 0.0 || defaultMinScore > 1.0) {
            throw new IllegalArgumentException("unirag.retrieval.default-min-score 需在 0-1 之间");
        }
    }
}

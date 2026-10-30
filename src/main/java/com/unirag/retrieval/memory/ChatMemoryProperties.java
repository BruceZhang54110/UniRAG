package com.unirag.retrieval.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 会话记忆配置（specs/015-chat-memory/data-model.md §3）。
 *
 * @param enabled               记忆总开关；关闭时携带 sessionId 也保持无状态（FR-001）
 * @param budgetTokens          记忆窗口 token 预算（FR-004/005，运行时调整即时生效）；
 *                              计量由容器内统一的 {@code TokenCountEstimator} Bean
 *                              （ingestion 的 IngestionTokenizer）承担，不再单配模型名
 * @param recordNoContextRound  检索无命中（NO_CONTEXT）的轮次是否写入记忆（FR-006）
 * @param rewriteWithMemory     查询改写是否纳入短期记忆（clarify Q3）
 */
@ConfigurationProperties(prefix = "unirag.chat-memory")
public record ChatMemoryProperties(@DefaultValue("true") boolean enabled,
                                   @DefaultValue("2000") int budgetTokens,
                                   @DefaultValue("true") boolean recordNoContextRound,
                                   @DefaultValue("true") boolean rewriteWithMemory) {

    public ChatMemoryProperties {
        if (budgetTokens <= 0) {
            throw new IllegalArgumentException("unirag.chat-memory.budget-tokens 必须为正数");
        }
    }
}

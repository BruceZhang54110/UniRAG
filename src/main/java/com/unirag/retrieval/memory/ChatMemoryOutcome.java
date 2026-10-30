package com.unirag.retrieval.memory;

/**
 * 一次问答中记忆参与的结果摘要（specs/015-chat-memory/data-model.md §5）：由
 * {@code ChatGenerationStage} 写入 {@code StageContext.attributes}，供
 * {@code ChatMemoryObservationExtension} 落观测行。
 *
 * <p>仅计数与标志，不含消息原文（宪法 V）。
 *
 * @param messageCount 写回后记忆窗口消息数
 * @param evictedCount 本轮写入逐出的消息数（0 表示无逐出）
 * @param degraded     记忆读写失败、按无记忆完成的标记（FR-007）
 * @param reason       降级原因（仅异常类型名），未降级为 null
 */
public record ChatMemoryOutcome(int messageCount,
                                int evictedCount,
                                boolean degraded,
                                String reason) {

    public static final String CONTEXT_KEY = "chatMemory.outcome";
}

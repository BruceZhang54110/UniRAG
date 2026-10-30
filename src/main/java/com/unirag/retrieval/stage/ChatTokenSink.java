package com.unirag.retrieval.stage;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 流式问答的令牌汇（specs/016-sse-chat-streaming/data-model.md §2）：由 SSE 层注入
 * {@code StageContext.attributes}（键 {@link #CONTEXT_KEY}），让单例的
 * {@code ChatGenerationStage} 按请求感知流式出口。
 *
 * @param deltaConsumer 回答增量出口（SSE 层推给客户端）
 * @param cancelled     客户端中止标志；为 true 时生成阶段停止推送且跳过记忆写回（FR-008）
 */
public record ChatTokenSink(Consumer<String> deltaConsumer, AtomicBoolean cancelled) {

    public static final String CONTEXT_KEY = "chatStream.sink";

    /** 发送增量；客户端已中止时静默丢弃（发送方已不存在）。 */
    public void emit(String delta) {
        if (delta != null && !cancelled.get()) {
            deltaConsumer.accept(delta);
        }
    }

    public static ChatTokenSink of(Consumer<String> deltaConsumer) {
        return new ChatTokenSink(deltaConsumer, new AtomicBoolean(false));
    }
}

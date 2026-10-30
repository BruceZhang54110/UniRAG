package com.unirag.retrieval.observation;

import com.unirag.retrieval.memory.ChatMemoryOutcome;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.FailurePolicy;
import com.unirag.retrieval.pipeline.ObservationExtension;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageOutcome;
import com.unirag.retrieval.pipeline.StageStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 会话记忆观测（specs/015-chat-memory/research.md R8）：读取 {@link ChatMemoryOutcome}，
 * 以独立 {@code CHAT_MEMORY} 记录落库；未携带 sessionId 或记忆未参与的请求没有 outcome，
 * 不产生记录。detail 仅计数与标志，不含消息原文（宪法 V）。
 */
@Component
@ConditionalOnProperty(prefix = "unirag.observability", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ChatMemoryObservationExtension implements ObservationExtension {

    private final ObservationRecorder recorder;

    public ChatMemoryObservationExtension(ObservationRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void beforeStage(StageName stage, Object input, StageContext ctx) {
        // 记忆统计由 ChatGenerationStage 写入 outcome，无需在 before 留痕
    }

    @Override
    public void afterStage(StageName stage, Object output, StageOutcome outcome, StageContext ctx) {
        ChatMemoryOutcome memory = ctx.get(ChatMemoryOutcome.CONTEXT_KEY, ChatMemoryOutcome.class);
        if (memory == null) {
            return;
        }
        StageResult result = new StageResult(StageName.CHAT_MEMORY,
                null,
                memory.degraded() ? StageStatus.DEGRADED : StageStatus.SUCCESS,
                outcome == null ? null : outcome.durationMs(),
                null,
                null,
                memory.messageCount(),
                memory.reason());
        recorder.record(ctx, result, detail(memory));
    }

    private static String detail(ChatMemoryOutcome memory) {
        return "{\"evicted\":" + memory.evictedCount()
                + ",\"degraded\":" + memory.degraded()
                + ",\"messageCount\":" + memory.messageCount() + "}";
    }

    @Override
    public Set<StageName> stages() {
        return Set.of(StageName.CHAT_GENERATION);
    }

    @Override
    public FailurePolicy failurePolicy() {
        return FailurePolicy.FAIL_OPEN;
    }
}

package com.unirag.retrieval.web;

import com.unirag.common.tenant.TenantContext;
import com.unirag.common.web.ApiResponse;
import com.unirag.retrieval.memory.ChatMemoryFactory;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.observation.ObservationProperties;
import com.unirag.retrieval.observation.ObservationRecorder;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话记忆管理接口（specs/015-chat-memory/contracts/clear-memory-endpoint.md，FR-009）：
 * {@code DELETE /api/v1/chat-memories/{sessionId}} 清空当前租户指定会话的记忆。
 *
 * <p>凭证与记忆读写同级：租户标识 + 会话标识即可（clarify Q1）；幂等，只影响指定会话。
 * 清空动作落一条 {@code CHAT_MEMORY} 观测（可经观测开关关闭），best-effort 不影响接口语义。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class ChatMemoryController {

    private final TenantContext tenantContext;
    private final ChatMemoryFactory chatMemoryFactory;
    private final ObservationRecorder recorder;
    private final ObservationProperties observationProperties;

    public ChatMemoryController(TenantContext tenantContext, ChatMemoryFactory chatMemoryFactory,
                                ObservationRecorder recorder, ObservationProperties observationProperties) {
        this.tenantContext = tenantContext;
        this.chatMemoryFactory = chatMemoryFactory;
        this.recorder = recorder;
        this.observationProperties = observationProperties;
    }

    @DeleteMapping("/chat-memories/{sessionId}")
    public ApiResponse<Void> clear(@PathVariable String sessionId) {
        chatMemoryFactory.clear(tenantContext.getTenantId(), sessionId);
        recordClear(sessionId);
        return ApiResponse.ok(null);
    }

    /** 清空留痕：traceId 可查（FR-009），detail 只记 cleared 标志，不含消息内容（宪法 V）。 */
    private void recordClear(String sessionId) {
        if (!observationProperties.isEnabled()) {
            return;
        }
        try {
            StageContext ctx = new StageContext(StageContext.newTraceId(), StageResult.KIND_QA,
                    tenantContext.getTenantCode(), tenantContext.getTenantId(), sessionId, null, null);
            recorder.record(ctx, new StageResult(StageName.CHAT_MEMORY, null, StageStatus.SUCCESS,
                    0L, null, null, null, null), "{\"cleared\":true}");
        } catch (RuntimeException e) {
            log.warn("[chat-memory] 清空观测写入失败: tenant={}, type={}",
                    tenantContext.getTenantCode(), e.getClass().getName());
        }
    }
}

package com.unirag.retrieval.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.common.tenant.TenantContext;
import com.unirag.common.web.ApiException;
import com.unirag.rag.QaService;
import com.unirag.rag.web.AskRequest;
import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.RetrievalProperties;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.stage.ChatTokenSink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式问答端点（specs/016-sse-chat-streaming/contracts/ask-stream-endpoint.md，FR-001/002/003）：
 * {@code POST /api/v1/ask/stream} 与非流式同一问答编排（复用 {@link QaService}，不复制逻辑），
 * 响应为 SSE 事件流——{@code message×N → done}，失败走 {@code error} 事件后正常关闭。
 *
 * <p>traceId 在进入时生成并写入 {@code X-Trace-Id} 响应头（与 done 事件一致，FR-009）；
 * 问答链在专用线程执行（阻塞调用不占容器请求线程）；客户端断开（发送抛异常）置
 * 中止标志，生成阶段停止推送且该轮不入记忆（FR-008）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class AskStreamController {

    private final QaService qaService;
    private final TenantContext tenantContext;
    private final RetrievalProperties retrievalProperties;
    private final ObjectMapper objectMapper;
    private final Executor chatStreamExecutor;
    private final ChatStreamProperties properties;

    public AskStreamController(QaService qaService, TenantContext tenantContext,
                               RetrievalProperties retrievalProperties,
                               ObjectMapper objectMapper,
                               @Qualifier("chatStreamExecutor") Executor chatStreamExecutor,
                               ChatStreamProperties properties) {
        this.qaService = qaService;
        this.tenantContext = tenantContext;
        this.retrievalProperties = retrievalProperties;
        this.objectMapper = objectMapper;
        this.chatStreamExecutor = chatStreamExecutor;
        this.properties = properties;
    }

    @PostMapping("/ask/stream")
    public ResponseEntity<SseEmitter> askStream(@RequestBody AskRequest request) {
        String traceId = StageContext.newTraceId();
        // 请求作用域 Bean 不能跨线程：租户身份在请求线程内取出，以值形式传入异步任务
        String tenantCode = tenantContext.getTenantCode();
        Long tenantId = tenantContext.getTenantId();
        SseEmitter emitter = new SseEmitter(properties.emitterTimeout().toMillis());
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ChatTokenSink sink = new ChatTokenSink(delta -> sendDelta(emitter, delta, cancelled), cancelled);
        try {
            chatStreamExecutor.execute(() -> runStreaming(
                    request.toModel(retrievalProperties.defaultMinScore()), sink, traceId,
                    tenantCode, tenantId, emitter));
        } catch (RuntimeException e) {
            // 线程池拒绝（过载）：快速失败走 error 事件，背压取舍见 ChatStreamExecutorConfig
            emitter.completeWithError(e);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MediaType.TEXT_EVENT_STREAM_VALUE))
                .header("Cache-Control", "no-cache")
                .header("X-Trace-Id", traceId)
                .body(emitter);
    }

    private void runStreaming(com.unirag.retrieval.model.QaRequest request, ChatTokenSink sink,
                              String traceId, String tenantCode, Long tenantId, SseEmitter emitter) {
        try {
            QaService.QaResult result = qaService.ask(request, sink, traceId, tenantCode, tenantId);
            sendEvent(emitter, "done", donePayload(result));
            emitter.complete();
        } catch (ApiException e) {
            sendError(emitter, e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[chat-stream] 流式问答失败: trace={}, type={}", traceId, e.getClass().getName());
            sendError(emitter, "INTERNAL_ERROR", "问答处理失败");
        }
    }

    private void sendDelta(SseEmitter emitter, String delta, AtomicBoolean cancelled) {
        try {
            emitter.send(SseEmitter.event().name("message")
                    .data(toJson(Map.of("delta", delta)), MediaType.APPLICATION_JSON));
        } catch (RuntimeException | IOException e) {
            // 客户端断开/连接失效：置中止标志，生成阶段随之停止（FR-008）
            cancelled.set(true);
        }
    }

    private void sendError(SseEmitter emitter, String code, String message) {
        try {
            sendEvent(emitter, "error",
                    toJson(Map.of("code", code, "message", Redaction.sanitize(message, 500))));
        } catch (RuntimeException sendFailure) {
            log.debug("[chat-stream] error 事件发送失败（客户端已断开）: type={}", sendFailure.getClass().getName());
        }
        emitter.complete();
    }

    private void sendEvent(SseEmitter emitter, String name, String json) throws RuntimeException {
        try {
            emitter.send(SseEmitter.event().name(name).data(json, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new IllegalStateException("SSE send failed", e);
        }
    }

    private String donePayload(QaService.QaResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", result.traceId());
        payload.put("answer", result.answer().answer());
        payload.put("citations", result.answer().citations());
        payload.put("degraded", result.answer().degraded());
        payload.put("degradeReason", result.answer().degradeReason());
        return toJson(payload);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SSE payload 序列化失败", e);
        }
    }
}

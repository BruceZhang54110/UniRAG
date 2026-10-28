package com.unirag.rag.route;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.rag.classify.ClassificationOutcome;
import com.unirag.rag.classify.ClassificationStatus;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.observation.ObservationRecorder;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ROUTE 观测写入器（contracts/rag-orchestration.md §1）：在执行前写入一条独立观测，
 * best-effort，不绑定传统检索流水线，也不新增第二套观测入口。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "unirag.observability", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RouteObservationWriter {

    private final ObservationRecorder recorder;
    private final ObjectMapper objectMapper;

    public RouteObservationWriter(ObservationRecorder recorder, ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    public void write(StageContext ctx, ClassificationOutcome outcome, RouteDecision decision) {
        try {
            String detail = objectMapper.writeValueAsString(RouteObservationDetail.from(outcome, decision));
            StageResult result = new StageResult(StageName.ROUTE,
                    decision.targetParadigm().name(),
                    decision.degraded() ? StageStatus.DEGRADED : StageStatus.SUCCESS,
                    decision.classificationDurationMs(),
                    null,
                    null,
                    outcome.evidence().size(),
                    primaryReason(outcome, decision));
            recorder.record(ctx, result, detail);
        } catch (JsonProcessingException e) {
            log.warn("ROUTE observation serialization failed: traceId={}, tenant={}, type={}",
                    ctx.traceId(), ctx.tenantCode(), e.getClass().getName());
        } catch (RuntimeException e) {
            log.warn("ROUTE observation write failed: traceId={}, tenant={}, type={}",
                    ctx.traceId(), ctx.tenantCode(), e.getClass().getName());
        }
    }

    private static String primaryReason(ClassificationOutcome outcome, RouteDecision decision) {
        if (outcome.status() != ClassificationStatus.SUCCESS) {
            return outcome.reasonCode();
        }
        return decision.reasonCodes().isEmpty() ? null : decision.reasonCodes().get(0);
    }
}

package com.unirag.retrieval.observation;

import com.unirag.infrastructure.persistence.entity.RetrievalObservation;
import com.unirag.infrastructure.persistence.service.RetrievalObservationService;
import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 观测记录器（FR-006/FR-008，research.md R3）：把阶段结果 best-effort 落库。
 *
 * <p>写库异常一律被捕获并降级为进程日志，并尝试记录降级事件本身；MUST NOT 影响检索/问答主链路。
 * {@code error_message} 与 {@code query_digest} 落库前统一脱敏与截断，日志只记类型与定位信息，
 * 绝不输出异常 message，避免配置原文或密钥外泄（原则 V）。
 */
@Slf4j
@Service
public class ObservationRecorder {

    static final int QUERY_DIGEST_MAX_LENGTH = 500;

    private static final int TARGET_KEY_MAX_LENGTH = 128;

    private static final int SESSION_ID_MAX_LENGTH = 128;

    private static final int DETAIL_MAX_LENGTH = 2000;

    private final RetrievalObservationService observationService;
    private final ObservationProperties properties;

    public ObservationRecorder(RetrievalObservationService observationService,
                               ObservationProperties properties) {
        this.observationService = observationService;
        this.properties = properties;
    }

    public void record(StageContext ctx, StageResult result) {
        record(ctx, result, null);
    }

    /** 带重排明细的写入；detail 只允许 ID/分数/keep-drop，落库前截断。 */
    public void record(StageContext ctx, StageResult result, String detail) {
        try {
            observationService.save(toEntity(ctx, result, detail));
        } catch (Exception e) {
            recordDegradation(ctx, result, e);
        }
    }

    /** 降级事件本身也尝试记录；若连它都写不进去，只留进程日志（research.md R3）。 */
    private void recordDegradation(StageContext ctx, StageResult result, Exception cause) {
        log.warn("观测写库失败，降级为进程日志: traceId={}, tenant={}, stage={}, type={}",
                ctx.traceId(), ctx.tenantCode(), result.stage(), cause.getClass().getName());
        StageResult degraded = new StageResult(result.stage(), result.targetKey(), StageStatus.DEGRADED,
                result.durationMs(), result.hitCount(), result.topScore(), result.itemCount(),
                "观测写库降级: " + cause.getClass().getSimpleName());
        try {
            observationService.save(toEntity(ctx, degraded, null));
        } catch (Exception second) {
            log.warn("观测降级事件记录亦失败: traceId={}, tenant={}, stage={}, type={}",
                    ctx.traceId(), ctx.tenantCode(), result.stage(), second.getClass().getName());
        }
    }

    private RetrievalObservation toEntity(StageContext ctx, StageResult result, String detail) {
        RetrievalObservation entity = new RetrievalObservation();
        entity.setTraceId(ctx.traceId());
        entity.setTenantId(ctx.tenantId());
        entity.setSessionId(Redaction.sanitize(ctx.sessionId(), SESSION_ID_MAX_LENGTH));
        entity.setKind(ctx.kind());
        entity.setStage(result.stage().name());
        entity.setTargetKey(Redaction.sanitize(result.targetKey(), TARGET_KEY_MAX_LENGTH));
        entity.setStatus(result.status().name());
        entity.setDurationMs(durationOf(result.durationMs()));
        entity.setHitCount(result.hitCount());
        entity.setTopScore(result.topScore());
        entity.setItemCount(result.itemCount());
        entity.setErrorMessage(Redaction.sanitize(result.errorMessage(), properties.getMaxErrorMessageLength()));
        entity.setDetail(Redaction.sanitize(detail, DETAIL_MAX_LENGTH));
        entity.setQueryDigest(Redaction.sanitize(ctx.queryDigest(), QUERY_DIGEST_MAX_LENGTH));
        entity.setDocumentId(ctx.documentId());
        entity.setCreatedAt(new Date());
        return entity;
    }

    private static Integer durationOf(Long durationMs) {
        if (durationMs == null) {
            return null;
        }
        return durationMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : durationMs.intValue();
    }
}

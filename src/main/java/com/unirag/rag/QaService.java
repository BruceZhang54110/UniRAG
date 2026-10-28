package com.unirag.rag;

import com.unirag.common.tenant.TenantContext;
import com.unirag.rag.classify.ClassificationOutcome;
import com.unirag.rag.classify.ClassificationRunner;
import com.unirag.rag.model.RagRequest;
import com.unirag.rag.model.RagResult;
import com.unirag.rag.route.RagRoute;
import com.unirag.rag.route.RagRouter;
import com.unirag.rag.route.RouteObservationWriter;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.observation.ObservationMetrics;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 唯一问答编排入口（014）：租户定位 → 统一请求 → 查询分类 → 能力路由 → ROUTE 观测 → 单次实际范式执行。
 * 对外仍保持既有 ask 契约，路由结构不泄漏到响应体。
 */
@Service
public class QaService {

    private final TenantContext tenantContext;
    private final ObservationMetrics metrics;
    private final ClassificationRunner classificationRunner;
    private final RagRouter ragRouter;
    private final ObjectProvider<RouteObservationWriter> routeObservationWriter;

    public QaService(TenantContext tenantContext,
                     ObservationMetrics metrics,
                     ClassificationRunner classificationRunner,
                     RagRouter ragRouter,
                     ObjectProvider<RouteObservationWriter> routeObservationWriter) {
        this.tenantContext = tenantContext;
        this.metrics = metrics;
        this.classificationRunner = classificationRunner;
        this.ragRouter = ragRouter;
        this.routeObservationWriter = routeObservationWriter;
    }

    public QaResult ask(QaRequest request) {
        String tenantCode = tenantContext.getTenantCode();
        Long tenantId = tenantContext.getTenantId();
        String traceId = StageContext.newTraceId();
        StageContext ctx = new StageContext(traceId, StageResult.KIND_QA, tenantCode, tenantId,
                request.sessionId(), request.question(), null);
        RagRequest ragRequest = RagRequest.from(request);
        long startedAt = System.nanoTime();
        try {
            ClassificationOutcome outcome = classificationRunner.classify(request.question());
            RagRoute route = ragRouter.route(tenantCode, outcome);
            recordRoute(tenantCode, ctx, outcome, route);
            RagResult result = route.executor().execute(ragRequest, ctx);
            QaAnswer answer = result.toQaAnswer();
            metrics.recordQa(tenantCode, answer.degraded() ? StageStatus.DEGRADED : StageStatus.SUCCESS,
                    elapsed(startedAt));
            return new QaResult(traceId, answer);
        } catch (RuntimeException e) {
            metrics.recordQa(tenantCode, StageStatus.FAILED, elapsed(startedAt));
            throw e;
        }
    }

    /** 路由观测与指标是主链路横切关注点，任何失败不得替换问答业务结果。 */
    private void recordRoute(String tenantCode, StageContext ctx, ClassificationOutcome outcome, RagRoute route) {
        try {
            RouteObservationWriter writer = routeObservationWriter.getIfAvailable();
            if (writer != null) {
                writer.write(ctx, outcome, route.decision());
            }
            metrics.recordRagRoute(tenantCode,
                    route.decision().degraded() ? StageStatus.DEGRADED : StageStatus.SUCCESS,
                    Duration.ofMillis(outcome.durationMs()));
        } catch (RuntimeException ignored) {
            // 宪法原则 V/观测 best-effort：路由观测失败只保留受控日志，不影响问答。
        }
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    /** 问答结果（contracts/retrieval-api.md §3）。 */
    public record QaResult(String traceId, QaAnswer answer) {
    }
}

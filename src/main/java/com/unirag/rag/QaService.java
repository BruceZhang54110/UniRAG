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
import com.unirag.retrieval.stage.ChatTokenSink;
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
        return ask(request, null, StageContext.newTraceId(),
                tenantContext.getTenantCode(), tenantContext.getTenantId());
    }

    /**
     * 流式问答入口（016-sse-chat-streaming）：traceId 由调用方（SSE 端点）生成以便
     * 先行写入响应头；令牌汇注入阶段上下文，生成阶段据此逐段推送（可空，空即非流式）。
     */
    public QaResult ask(QaRequest request, ChatTokenSink tokenSink, String traceId) {
        return ask(request, tokenSink, traceId,
                tenantContext.getTenantCode(), tenantContext.getTenantId());
    }

    /**
     * 异步流式入口：请求作用域的 {@link TenantContext} 不能跨线程使用，租户身份由
     * SSE 端点在请求线程内取出后显式传入（值对象，非代理）。
     */
    public QaResult ask(QaRequest request, ChatTokenSink tokenSink, String traceId,
                        String tenantCode, Long tenantId) {
        StageContext ctx = new StageContext(traceId, StageResult.KIND_QA, tenantCode, tenantId,
                request.sessionId(), request.question(), null);
        if (tokenSink != null) {
            ctx.put(ChatTokenSink.CONTEXT_KEY, tokenSink);
        }
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

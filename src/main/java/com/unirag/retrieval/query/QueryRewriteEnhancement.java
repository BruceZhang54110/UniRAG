package com.unirag.retrieval.query;

import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.observation.ObservationMetrics;
import com.unirag.retrieval.pipeline.EnhancementExtension;
import com.unirag.retrieval.pipeline.FailurePolicy;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageStatus;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;

/**
 * 查询改写扩展（contracts/query-rewrite.md §3）：在 {@code QUERY_EMBEDDING} 之前把用户原问题
 * 转成一条检索查询，只替换 {@code retrievalQuery}。
 *
 * <p>可选优化，失败一律 fail-open：回退原问题继续检索，并把 {@link QueryRewriteOutcome} 写入
 * 上下文供观测扩展落行。原始 {@code query} 永远不被修改。
 */
@Slf4j
@Component
public class QueryRewriteEnhancement implements EnhancementExtension {

    private static final int ORDER = 50;

    private final TenantResourceRouter router;
    private final QueryRewriteSupport support;
    private final QueryRewriteProperties properties;
    private final ObservationMetrics metrics;

    public QueryRewriteEnhancement(TenantResourceRouter router, QueryRewriteSupport support,
                                   QueryRewriteProperties properties, ObservationMetrics metrics) {
        this.router = router;
        this.support = support;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Set<StageName> stages() {
        return Set.of(StageName.QUERY_EMBEDDING);
    }

    @Override
    public FailurePolicy failurePolicy() {
        return FailurePolicy.FAIL_OPEN;
    }

    @Override
    public Object beforeStage(StageName stage, Object input, StageContext ctx) {
        if (!(input instanceof RetrievalRequest request) || !request.rewrite()) {
            return input;
        }
        if (request.query().length() > properties.maxInputChars()) {
            log.debug("[retrieval] trace={} QUERY_REWRITE skipped, query too long chars={}",
                    ctx.traceId(), request.query().length());
            return input;
        }

        long startedAt = System.nanoTime();
        String modelKey = null;
        String modelSource = null;
        try {
            modelKey = support.primaryKey(ctx.tenantCode()).orElse(null);
            QueryTransformer transformer = router.getPrimary(ctx.tenantCode(),
                    ResourceType.QUERY_REWRITE_MODEL, QueryTransformer.class);
            if (transformer instanceof LlmQueryTransformer llm) {
                modelSource = llm.modelSource();
            }
            Collection<Query> transformed = transformer.transform(Query.from(request.query()));
            String rejected = rejectReason(transformed, request.query());
            if (rejected != null) {
                return degraded(ctx, request, modelKey, modelSource, startedAt, rejected);
            }

            String rewritten = firstText(transformed).strip();
            long elapsed = elapsedMillis(startedAt);
            record(ctx, request, modelKey, modelSource, elapsed, StageStatus.SUCCESS, null, rewritten);
            log.debug("[retrieval] trace={} QUERY_REWRITE success originalChars={} retrievalChars={} shadow={} model={}",
                    ctx.traceId(), request.query().length(), rewritten.length(), properties.shadow(),
                    modelSource == null ? "unknown" : modelSource);
            return properties.shadow() ? input : request.withRetrievalQuery(rewritten);
        } catch (RuntimeException e) {
            return degraded(ctx, request, modelKey, modelSource, startedAt, Redaction.describe(e));
        }
    }

    @Override
    public Object afterStage(StageName stage, Object output, StageContext ctx) {
        return output;
    }

    private Object degraded(StageContext ctx, RetrievalRequest request, String modelKey, String modelSource,
                            long startedAt, String reason) {
        long elapsed = elapsedMillis(startedAt);
        record(ctx, request, modelKey, modelSource, elapsed, StageStatus.DEGRADED, reason, request.query());
        log.warn("[retrieval] trace={} QUERY_REWRITE degraded, fallback to original: {}",
                ctx.traceId(), reason);
        return request;
    }

    private void record(StageContext ctx, RetrievalRequest request, String modelKey, String modelSource,
                        long elapsed, StageStatus status, String reason, String retrievalText) {
        String detail = detail(modelSource, request.query(), retrievalText);
        QueryRewriteOutcome outcome = new QueryRewriteOutcome(modelKey, modelSource,
                request.query().length(), retrievalText.length(), elapsed, status,
                Redaction.sanitize(reason, Redaction.MAX_LENGTH), properties.shadow(), detail);
        ctx.put(QueryRewriteOutcome.CONTEXT_KEY, outcome);
        metrics.recordQueryRewrite(ctx.tenantCode(), status, Duration.ofMillis(elapsed));
    }

    private String detail(String modelSource, String original, String retrieval) {
        String preview = Redaction.sanitize(retrieval.strip().replaceAll("\\s+", " "),
                properties.previewChars());
        return "shadow=" + properties.shadow()
                + ";model=" + (modelSource == null ? "unknown" : modelSource)
                + ";prompt=" + LlmQueryTransformer.PROMPT_VERSION
                + ";originalChars=" + original.length()
                + ";retrievalChars=" + retrieval.length()
                + ";preview=" + (preview == null ? "" : preview);
    }

    private String rejectReason(Collection<Query> transformed, String original) {
        if (transformed == null || transformed.isEmpty()) {
            return RewriteValidator.EMPTY_OUTPUT;
        }
        if (transformed.size() > 1) {
            return RewriteValidator.MULTIPLE_QUERIES;
        }
        Query only = transformed.iterator().next();
        String text = only == null ? null : only.text();
        if (text == null || text.isBlank()) {
            return RewriteValidator.EMPTY_OUTPUT;
        }
        return RewriteValidator.validate(original, text, properties).orElse(null);
    }

    private static String firstText(Collection<Query> transformed) {
        return transformed.iterator().next().text();
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}

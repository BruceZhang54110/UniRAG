package com.unirag.retrieval.query;

import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.memory.ChatMemoryFactory;
import com.unirag.retrieval.memory.ChatMemoryProperties;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.observation.ObservationMetrics;
import com.unirag.retrieval.pipeline.EnhancementExtension;
import com.unirag.retrieval.pipeline.FailurePolicy;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageStatus;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 查询改写扩展（contracts/query-rewrite.md §3）：在 {@code QUERY_EMBEDDING} 之前把用户原问题
 * 转成一条检索查询，只替换 {@code retrievalQuery}。
 *
 * <p>可选优化，失败一律 fail-open：回退原问题继续检索，并把 {@link QueryRewriteOutcome} 写入
 * 上下文供观测扩展落行。原始 {@code query} 永远不被修改。
 *
 * <p>会话记忆（015-chat-memory，rewrite-v2）：携带 sessionId 且开启
 * {@code rewrite-with-memory} 时，改写输入带上短期记忆窗口摘要，指代型追问先消歧再改写；
 * 记忆读取失败按既有 FAIL_OPEN 降级为单问改写。
 */
@Slf4j
@Component
public class QueryRewriteEnhancement implements EnhancementExtension {

    private static final int ORDER = 50;

    private final TenantResourceRouter router;
    private final QueryRewriteSupport support;
    private final QueryRewriteProperties properties;
    private final ObservationMetrics metrics;
    private final ChatMemoryFactory chatMemoryFactory;
    private final ChatMemoryProperties chatMemoryProperties;

    public QueryRewriteEnhancement(TenantResourceRouter router, QueryRewriteSupport support,
                                   QueryRewriteProperties properties, ObservationMetrics metrics,
                                   ChatMemoryFactory chatMemoryFactory,
                                   ChatMemoryProperties chatMemoryProperties) {
        this.router = router;
        this.support = support;
        this.properties = properties;
        this.metrics = metrics;
        this.chatMemoryFactory = chatMemoryFactory;
        this.chatMemoryProperties = chatMemoryProperties;
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
        String promptVersion = LlmQueryTransformer.PROMPT_VERSION;
        try {
            modelKey = support.primaryKey(ctx.tenantCode()).orElse(null);
            QueryTransformer transformer = router.getPrimary(ctx.tenantCode(),
                    ResourceType.QUERY_REWRITE_MODEL, QueryTransformer.class);
            if (transformer instanceof LlmQueryTransformer llm) {
                modelSource = llm.modelSource();
            }
            Collection<Query> transformed;
            List<ChatMessage> history = conversationOf(ctx);
            if (!history.isEmpty() && transformer instanceof LlmQueryTransformer llm) {
                promptVersion = LlmQueryTransformer.CONVERSATIONAL_PROMPT_VERSION;
                transformed = llm.transform(Query.from(request.query()), history);
            } else {
                transformed = transformer.transform(Query.from(request.query()));
            }
            String rejected = rejectReason(transformed, request.query());
            if (rejected != null) {
                return degraded(ctx, request, modelKey, modelSource, promptVersion, startedAt, rejected);
            }

            String rewritten = firstText(transformed).strip();
            long elapsed = elapsedMillis(startedAt);
            record(ctx, request, modelKey, modelSource, promptVersion, elapsed, StageStatus.SUCCESS, null, rewritten);
            log.debug("[retrieval] trace={} QUERY_REWRITE success originalChars={} retrievalChars={} shadow={} model={}",
                    ctx.traceId(), request.query().length(), rewritten.length(), properties.shadow(),
                    modelSource == null ? "unknown" : modelSource);
            return properties.shadow() ? input : request.withRetrievalQuery(rewritten);
        } catch (RuntimeException e) {
            return degraded(ctx, request, modelKey, modelSource, promptVersion, startedAt, Redaction.describe(e));
        }
    }

    /** 短期记忆窗口（clarify Q3）；任何失败返回空列表，由单问改写兜底。 */
    private List<ChatMessage> conversationOf(StageContext ctx) {
        if (!chatMemoryProperties.rewriteWithMemory()) {
            return List.of();
        }
        try {
            Optional<ChatMemory> memory = chatMemoryFactory.get(ctx.tenantId(), ctx.sessionId());
            if (memory.isEmpty()) {
                return List.of();
            }
            List<ChatMessage> messages = memory.get().messages();
            return messages == null ? List.of() : messages;
        } catch (RuntimeException e) {
            log.warn("[chat-memory] 改写记忆读取失败，按单问改写: trace={}, type={}",
                    ctx.traceId(), e.getClass().getName());
            return List.of();
        }
    }

    @Override
    public Object afterStage(StageName stage, Object output, StageContext ctx) {
        return output;
    }

    private Object degraded(StageContext ctx, RetrievalRequest request, String modelKey, String modelSource,
                            String promptVersion, long startedAt, String reason) {
        long elapsed = elapsedMillis(startedAt);
        record(ctx, request, modelKey, modelSource, promptVersion, elapsed, StageStatus.DEGRADED, reason, request.query());
        log.warn("[retrieval] trace={} QUERY_REWRITE degraded, fallback to original: {}",
                ctx.traceId(), reason);
        return request;
    }

    private void record(StageContext ctx, RetrievalRequest request, String modelKey, String modelSource,
                        String promptVersion, long elapsed, StageStatus status, String reason,
                        String retrievalText) {
        String detail = detail(modelSource, promptVersion, request.query(), retrievalText);
        QueryRewriteOutcome outcome = new QueryRewriteOutcome(modelKey, modelSource,
                request.query().length(), retrievalText.length(), elapsed, status,
                Redaction.sanitize(reason, Redaction.MAX_LENGTH), properties.shadow(), detail);
        ctx.put(QueryRewriteOutcome.CONTEXT_KEY, outcome);
        metrics.recordQueryRewrite(ctx.tenantCode(), status, Duration.ofMillis(elapsed));
    }

    private String detail(String modelSource, String promptVersion, String original, String retrieval) {
        String preview = Redaction.sanitize(retrieval.strip().replaceAll("\\s+", " "),
                properties.previewChars());
        return "shadow=" + properties.shadow()
                + ";model=" + (modelSource == null ? "unknown" : modelSource)
                + ";prompt=" + promptVersion
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

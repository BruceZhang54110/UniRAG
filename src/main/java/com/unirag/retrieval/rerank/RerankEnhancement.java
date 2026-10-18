package com.unirag.retrieval.rerank;

import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.observation.ObservationMetrics;
import com.unirag.retrieval.pipeline.EnhancementExtension;
import com.unirag.retrieval.pipeline.FailurePolicy;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageStatus;
import com.unirag.retrieval.stage.MergeResult;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 重排序扩展（contracts/rerank-model.md §3）：在 {@code MERGE} 之后对候选片段重新打分。
 *
 * <p>可选优化，失败一律 fail-open：回退到原向量顺序并截断到 {@code topK}。成功时把
 * {@link RerankOutcome} 写入上下文，由 {@code RerankObservationExtension} 落观测行。
 */
@Slf4j
@Component
public class RerankEnhancement implements EnhancementExtension {

    private static final int ORDER = 100;

    private static final int DETAIL_MAX_LENGTH = 2000;

    private final TenantResourceRouter router;
    private final RerankProperties properties;
    private final ObservationMetrics metrics;

    public RerankEnhancement(TenantResourceRouter router, RerankProperties properties,
                             ObservationMetrics metrics) {
        this.router = router;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public Object beforeStage(StageName stage, Object input, StageContext ctx) {
        return input;
    }

    @Override
    public Set<StageName> stages() {
        return Set.of(StageName.MERGE);
    }

    @Override
    public FailurePolicy failurePolicy() {
        return FailurePolicy.FAIL_OPEN;
    }

    @Override
    public Object afterStage(StageName stage, Object output, StageContext ctx) {
        if (!(output instanceof MergeResult merge)
                || !merge.request().rerank()
                || merge.chunks().isEmpty()) {
            return output;
        }

        long startedAt = System.nanoTime();
        int candidates = merge.chunks().size();
        try {
            ScoringModel model = router.getPrimary(ctx.tenantCode(), ResourceType.RERANK_MODEL, ScoringModel.class);
            if (model == null) {
                throw new IllegalStateException("rerank model unavailable");
            }
            List<TextSegment> segments = merge.chunks().stream()
                    .map(chunk -> TextSegment.from(chunk.text()))
                    .toList();
            Response<List<Double>> response = model.scoreAll(segments, merge.request().query());
            List<Double> scores = response == null ? null : response.content();
            validateScores(scores, candidates);

            List<Integer> rankedIndexes = IntStream.range(0, candidates)
                    .boxed()
                    .filter(index -> properties.minScore() == null
                            || scores.get(index) >= properties.minScore())
                    .sorted(scoreOrder(scores, merge.chunks()))
                    .limit(merge.request().topK())
                    .toList();
            List<RetrievedChunk> ranked = rankedIndexes.stream()
                    .map(index -> merge.chunks().get(index).withRerankScore(scores.get(index)))
                    .toList();

            long elapsed = elapsedMillis(startedAt);
            RerankOutcome outcome = new RerankOutcome(candidates, ranked.size(),
                    topScore(scores), elapsed, StageStatus.SUCCESS, null, properties.shadow(),
                    detail(merge.chunks(), scores, rankedIndexes));
            ctx.put(RerankOutcome.CONTEXT_KEY, outcome);
            metrics.recordRerank(ctx.tenantCode(), StageStatus.SUCCESS, Duration.ofMillis(elapsed));

            if (properties.shadow()) {
                return truncated(merge, merge.request().topK());
            }
            return new MergeResult(merge.request(), ranked, merge.searchedTables(), merge.failedTables());
        } catch (RuntimeException e) {
            long elapsed = elapsedMillis(startedAt);
            String reason = Redaction.describe(e);
            ctx.put(RerankOutcome.CONTEXT_KEY, new RerankOutcome(candidates,
                    Math.min(candidates, merge.request().topK()), null, elapsed,
                    StageStatus.DEGRADED, reason, properties.shadow(), ""));
            metrics.recordRerank(ctx.tenantCode(), StageStatus.DEGRADED, Duration.ofMillis(elapsed));
            log.warn("[retrieval] trace={} RERANK degraded, fallback to vector order: {}",
                    ctx.traceId(), reason);
            return truncated(merge, merge.request().topK());
        }
    }

    private static void validateScores(List<Double> scores, int expected) {
        if (scores == null || scores.size() != expected) {
            throw new IllegalStateException("rerank score count mismatch");
        }
        for (Double score : scores) {
            if (score == null || !Double.isFinite(score)) {
                throw new IllegalStateException("rerank returned invalid score");
            }
        }
    }

    private static Comparator<Integer> scoreOrder(List<Double> scores, List<RetrievedChunk> chunks) {
        return Comparator.comparingDouble((Integer index) -> scores.get(index)).reversed()
                .thenComparingLong(index -> chunks.get(index).documentId())
                .thenComparing(index -> chunks.get(index).pipelineKey())
                .thenComparingInt(index -> chunks.get(index).chunkIndex());
    }

    private static Double topScore(List<Double> scores) {
        return scores.stream().filter(Objects::nonNull).max(Double::compareTo).orElse(null);
    }

    private static MergeResult truncated(MergeResult merge, int limit) {
        if (merge.chunks().size() <= limit) {
            return merge;
        }
        return new MergeResult(merge.request(), merge.chunks().subList(0, limit),
                merge.searchedTables(), merge.failedTables());
    }

    private static String detail(List<RetrievedChunk> chunks, List<Double> scores, List<Integer> keptIndexes) {
        Set<Integer> kept = new HashSet<>(keptIndexes);
        StringBuilder detail = new StringBuilder();
        List<Integer> ordered = IntStream.range(0, chunks.size())
                .boxed()
                .sorted(scoreOrder(scores, chunks))
                .toList();
        for (Integer index : ordered) {
            RetrievedChunk chunk = chunks.get(index);
            String entry = chunk.documentId() + "/" + chunk.pipelineKey() + "/" + chunk.chunkIndex()
                    + "=" + String.format(Locale.ROOT, "%.4f", scores.get(index))
                    + ":" + (kept.contains(index) ? "keep" : "drop") + ";";
            if (detail.length() + entry.length() > DETAIL_MAX_LENGTH) {
                break;
            }
            detail.append(entry);
        }
        return detail.toString();
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}

package com.unirag.retrieval.observation;

import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.FailurePolicy;
import com.unirag.retrieval.pipeline.ObservationExtension;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageOutcome;
import com.unirag.retrieval.query.QueryRewriteOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 查询改写观测（contracts/query-rewrite.md §4）：读取 {@link QueryRewriteOutcome}，以独立
 * {@code QUERY_REWRITE} 记录落库；未启用改写的请求没有 outcome，不产生记录。
 */
@Component
@ConditionalOnProperty(prefix = "unirag.observability", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class QueryRewriteObservationExtension implements ObservationExtension {

    private final ObservationRecorder recorder;

    public QueryRewriteObservationExtension(ObservationRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void beforeStage(StageName stage, Object input, StageContext ctx) {
        // 改写耗时与明细由 QueryRewriteEnhancement 写入 outcome，无需在 before 留痕
    }

    @Override
    public void afterStage(StageName stage, Object output, StageOutcome outcome, StageContext ctx) {
        QueryRewriteOutcome rewrite = ctx.get(QueryRewriteOutcome.CONTEXT_KEY, QueryRewriteOutcome.class);
        if (rewrite == null) {
            return;
        }
        StageResult result = new StageResult(StageName.QUERY_REWRITE, rewrite.modelKey(), rewrite.status(),
                rewrite.durationMs(), rewrite.originalChars(), null, rewrite.retrievalChars(),
                rewrite.reason());
        recorder.record(ctx, result, rewrite.detail());
    }

    @Override
    public Set<StageName> stages() {
        return Set.of(StageName.QUERY_EMBEDDING);
    }

    @Override
    public FailurePolicy failurePolicy() {
        return FailurePolicy.FAIL_OPEN;
    }
}

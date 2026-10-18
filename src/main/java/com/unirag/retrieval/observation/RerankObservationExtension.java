package com.unirag.retrieval.observation;

import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.FailurePolicy;
import com.unirag.retrieval.pipeline.ObservationExtension;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageOutcome;
import com.unirag.retrieval.rerank.RerankOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 重排观测（contracts/rerank-model.md §4）：读取 {@link RerankOutcome}，以独立 {@code RERANK}
 * 记录落库；未启用重排的请求没有 outcome，不产生记录。
 */
@Component
@ConditionalOnProperty(prefix = "unirag.observability", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RerankObservationExtension implements ObservationExtension {

    private final ObservationRecorder recorder;

    public RerankObservationExtension(ObservationRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void beforeStage(StageName stage, Object input, StageContext ctx) {
        // 重排耗时与明细由 RerankEnhancement 写入 outcome，无需在 before 留痕
    }

    @Override
    public void afterStage(StageName stage, Object output, StageOutcome outcome, StageContext ctx) {
        RerankOutcome rerank = ctx.get(RerankOutcome.CONTEXT_KEY, RerankOutcome.class);
        if (rerank == null) {
            return;
        }
        StageResult result = new StageResult(StageName.RERANK,
                rerank.shadow() ? "shadow" : null,
                rerank.status(),
                rerank.durationMs(),
                rerank.candidates(),
                rerank.topScore(),
                rerank.kept(),
                rerank.reason());
        recorder.record(ctx, result, rerank.detail());
    }

    @Override
    public Set<StageName> stages() {
        return Set.of(StageName.MERGE);
    }

    @Override
    public FailurePolicy failurePolicy() {
        return FailurePolicy.FAIL_OPEN;
    }
}

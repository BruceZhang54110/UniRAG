package com.unirag.retrieval.observation;

import com.unirag.retrieval.model.ParentFetchOutcome;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.FailurePolicy;
import com.unirag.retrieval.pipeline.ObservationExtension;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 父块回填观测（020 contracts/parent-child-chunking.md §3）：读取 {@link ParentFetchOutcome}，
 * 以独立 {@code PARENT_FETCH} 记录落库；明细只含 id/序号与 keep/drop，禁含文本原文（原则 V）。
 */
@Component
@ConditionalOnProperty(prefix = "unirag.observability", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ParentFetchObservationExtension implements ObservationExtension {

    private final ObservationRecorder recorder;

    public ParentFetchObservationExtension(ObservationRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void beforeStage(StageName stage, Object input, StageContext ctx) {
        // 回填耗时与明细由 ParentFetchStage 写入 outcome，无需在 before 留痕
    }

    @Override
    public void afterStage(StageName stage, Object output, StageOutcome outcome, StageContext ctx) {
        ParentFetchOutcome fetch = ctx.get(ParentFetchOutcome.CONTEXT_KEY, ParentFetchOutcome.class);
        if (fetch == null) {
            return;
        }
        StageResult result = new StageResult(StageName.PARENT_FETCH,
                null,
                fetch.status(),
                fetch.durationMs(),
                fetch.mergedFromChildren(),
                null,
                fetch.fetchedParents(),
                fetch.reason());
        recorder.record(ctx, result, fetch.detail());
    }

    @Override
    public Set<StageName> stages() {
        return Set.of(StageName.PARENT_FETCH);
    }

    @Override
    public FailurePolicy failurePolicy() {
        return FailurePolicy.FAIL_OPEN;
    }
}

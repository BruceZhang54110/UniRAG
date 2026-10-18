package com.unirag.retrieval.observation;

import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageOutcome;
import com.unirag.retrieval.pipeline.StageStatus;
import com.unirag.retrieval.rerank.RerankOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RerankObservationExtensionTest {

    private final ObservationRecorder recorder = mock(ObservationRecorder.class);
    private final RerankObservationExtension extension = new RerankObservationExtension(recorder);
    private final StageContext context = new StageContext(
            "trace-1", "QA", "tenant", 1L, null, "查询", null);

    @Test
    void 把重排结果落为独立的RERANK记录() {
        context.put(RerankOutcome.CONTEXT_KEY, new RerankOutcome(4, 2, 0.9, 12,
                StageStatus.SUCCESS, null, true, "1/main/0=0.9000:keep;"));
        ArgumentCaptor<StageResult> result = ArgumentCaptor.forClass(StageResult.class);

        extension.afterStage(StageName.MERGE, null, StageOutcome.success(1), context);

        verify(recorder).record(eq(context), result.capture(), eq("1/main/0=0.9000:keep;"));
        StageResult recorded = result.getValue();
        assertThat(recorded.stage()).isEqualTo(StageName.RERANK);
        assertThat(recorded.targetKey()).isEqualTo("shadow");
        assertThat(recorded.status()).isEqualTo(StageStatus.SUCCESS);
        assertThat(recorded.hitCount()).isEqualTo(4);
        assertThat(recorded.topScore()).isEqualTo(0.9);
        assertThat(recorded.itemCount()).isEqualTo(2);
    }

    @Test
    void 没有重排结果时不写记录() {
        extension.afterStage(StageName.MERGE, null, StageOutcome.success(1), context);

        verifyNoInteractions(recorder);
    }

    @Test
    void 降级结果保留原因且targetKey为空() {
        context.put(RerankOutcome.CONTEXT_KEY, new RerankOutcome(4, 2, null, 12,
                StageStatus.DEGRADED, "连接超时", false, ""));
        ArgumentCaptor<StageResult> result = ArgumentCaptor.forClass(StageResult.class);

        extension.afterStage(StageName.MERGE, null, StageOutcome.success(1), context);

        verify(recorder).record(eq(context), result.capture(), any());
        assertThat(result.getValue().targetKey()).isNull();
        assertThat(result.getValue().status()).isEqualTo(StageStatus.DEGRADED);
        assertThat(result.getValue().errorMessage()).isEqualTo("连接超时");
    }
}

package com.unirag.retrieval.rerank;

import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.observation.ObservationMetrics;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageStatus;
import com.unirag.retrieval.stage.MergeResult;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceNotFoundException;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RerankEnhancementTest {

    private static final String TENANT = "rerankUnit";

    private final TenantResourceRouter router = mock(TenantResourceRouter.class);
    private final ObservationMetrics metrics = mock(ObservationMetrics.class);
    private final ScoringModel model = mock(ScoringModel.class);
    private final StageContext context = new StageContext("trace-1", "QA", TENANT, 1L, null, "查询", null);

    @Test
    void 按重排分数降序并截断到topK() {
        RerankEnhancement enhancement = enhancement(false, 20, null);
        when(model.scoreAll(anyList(), anyString())).thenReturn(Response.from(List.of(0.1, 0.9, 0.5, 0.8)));
        MergeResult merge = merge(4, 2, 4, true);

        MergeResult result = (MergeResult) enhancement.afterStage(StageName.MERGE, merge, context);

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(1, 3);
        assertThat(result.chunks()).extracting(RetrievedChunk::rerankScore).containsExactly(0.9, 0.8);
        RerankOutcome outcome = outcome();
        assertThat(outcome.status()).isEqualTo(StageStatus.SUCCESS);
        assertThat(outcome.candidates()).isEqualTo(4);
        assertThat(outcome.kept()).isEqualTo(2);
        assertThat(outcome.shadow()).isFalse();
    }

    @Test
    void 可选阈值过滤后仍截断到topK() {
        RerankEnhancement enhancement = enhancement(false, 20, 0.6);
        when(model.scoreAll(anyList(), anyString())).thenReturn(Response.from(List.of(0.1, 0.9, 0.5, 0.8)));
        MergeResult merge = merge(4, 5, 5, true);

        MergeResult result = (MergeResult) enhancement.afterStage(StageName.MERGE, merge, context);

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(1, 3);
    }

    @Test
    void shadow模式返回原向量顺序但记录建议结果() {
        RerankEnhancement enhancement = enhancement(true, 20, null);
        when(model.scoreAll(anyList(), anyString())).thenReturn(Response.from(List.of(0.1, 0.9, 0.5, 0.8)));
        MergeResult merge = merge(4, 2, 4, true);

        MergeResult result = (MergeResult) enhancement.afterStage(StageName.MERGE, merge, context);

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(0, 1);
        RerankOutcome outcome = outcome();
        assertThat(outcome.shadow()).isTrue();
        assertThat(outcome.kept()).isEqualTo(2);
    }

    @Test
    void 重排失败时回退原向量顺序并截断到topK() {
        RerankEnhancement enhancement = enhancement(false, 20, null);
        when(model.scoreAll(anyList(), anyString())).thenThrow(new IllegalStateException("rerank down"));
        MergeResult merge = merge(4, 2, 4, true);

        MergeResult result = (MergeResult) enhancement.afterStage(StageName.MERGE, merge, context);

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(0, 1);
        RerankOutcome outcome = outcome();
        assertThat(outcome.status()).isEqualTo(StageStatus.DEGRADED);
        assertThat(outcome.kept()).isEqualTo(2);
        verify(metrics).recordRerank(eq(TENANT), eq(StageStatus.DEGRADED), any());
    }

    @Test
    void 租户无重排模型时回退且不调用模型() {
        RerankEnhancement enhancement = enhancement(false, 20, null);
        when(router.getPrimary(eq(TENANT), eq(ResourceType.RERANK_MODEL), eq(ScoringModel.class)))
                .thenThrow(TenantResourceNotFoundException.noPrimary(TENANT, ResourceType.RERANK_MODEL, 0));
        MergeResult merge = merge(4, 2, 4, true);

        MergeResult result = (MergeResult) enhancement.afterStage(StageName.MERGE, merge, context);

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(0, 1);
        assertThat(outcome().status()).isEqualTo(StageStatus.DEGRADED);
    }

    @Test
    void 未启用重排时原样返回且不调用模型() {
        RerankEnhancement enhancement = enhancement(false, 20, null);
        MergeResult merge = merge(4, 2, 4, false);

        MergeResult result = (MergeResult) enhancement.afterStage(StageName.MERGE, merge, context);

        assertThat(result).isSameAs(merge);
        verify(model, never()).scoreAll(anyList(), anyString());
        assertThat(outcome()).isNull();
    }

    @Test
    void 分数数量不匹配时回退() {
        RerankEnhancement enhancement = enhancement(false, 20, null);
        when(model.scoreAll(anyList(), anyString())).thenReturn(Response.from(List.of(0.9)));
        MergeResult merge = merge(4, 2, 4, true);

        MergeResult result = (MergeResult) enhancement.afterStage(StageName.MERGE, merge, context);

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkIndex).containsExactly(0, 1);
        assertThat(outcome().status()).isEqualTo(StageStatus.DEGRADED);
    }

    @Test
    void 同分时按文档标识兜底() {
        RerankEnhancement enhancement = enhancement(false, 20, null);
        when(model.scoreAll(anyList(), anyString())).thenReturn(Response.from(List.of(0.5, 0.5, 0.5, 0.5)));
        MergeResult merge = merge(4, 4, 4, true);

        MergeResult result = (MergeResult) enhancement.afterStage(StageName.MERGE, merge, context);

        assertThat(result.chunks()).extracting(RetrievedChunk::documentId).containsExactly(1L, 2L, 3L, 4L);
    }

    private RerankEnhancement enhancement(boolean shadow, int recallTopK, Double minScore) {
        when(router.getPrimary(eq(TENANT), eq(ResourceType.RERANK_MODEL), eq(ScoringModel.class))).thenReturn(model);
        return new RerankEnhancement(router, new RerankProperties(true, shadow, recallTopK, minScore), metrics);
    }

    private MergeResult merge(int chunkCount, int topK, int recallTopK, boolean rerank) {
        RetrievalRequest request = new RetrievalRequest("查询", null, null, topK, 0.0, recallTopK, rerank);
        List<RetrievedChunk> chunks = IntStream.range(0, chunkCount)
                .mapToObj(index -> new RetrievedChunk(index + 1L, "doc", "main", null,
                        index, 1.0 - index * 0.1, "text-" + index))
                .toList();
        return new MergeResult(request, chunks, 1, List.of());
    }

    private RerankOutcome outcome() {
        return context.get(RerankOutcome.CONTEXT_KEY, RerankOutcome.class);
    }
}

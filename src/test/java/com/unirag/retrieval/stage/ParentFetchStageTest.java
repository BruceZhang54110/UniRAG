package com.unirag.retrieval.stage;

import com.unirag.infrastructure.persistence.entity.DocumentChunk;
import com.unirag.infrastructure.persistence.entity.IngestionPipeline;
import com.unirag.infrastructure.persistence.service.DocumentChunkService;
import com.unirag.infrastructure.persistence.service.IngestionPipelineService;
import com.unirag.ingestion.IngestionTokenizer;
import com.unirag.retrieval.model.ParentFetchOutcome;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ParentFetchStage 单测（tasks.md T013）：父子分组去重、预算截断、父块缺失降级、
 * 单级命中透传、空输入、观测 Outcome 明细（禁含文本原文）。
 */
class ParentFetchStageTest {

    private static final Long TENANT_ID = 42L;

    private final DocumentChunkService chunkService = mock(DocumentChunkService.class);
    private final IngestionPipelineService pipelineService = mock(IngestionPipelineService.class);

    private final ParentFetchStage stage = new ParentFetchStage(
            chunkService, pipelineService, new IngestionTokenizer(),
            new com.unirag.retrieval.observation.ObservationMetrics(
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

    private StageContext context;
    private IngestionPipeline parentChildPipeline;

    @BeforeEach
    void setUp() {
        context = new StageContext(StageContext.newTraceId(), "QA", "t1", TENANT_ID, null, "问题", null);
        parentChildPipeline = pipeline("pc", "PARENT_CHILD", 5, 4000);
        IngestionPipeline singlePipeline = pipeline("single", "RECURSIVE", 5, 4000);
        when(pipelineService.findEnabledByTenant(TENANT_ID))
                .thenReturn(List.of(parentChildPipeline, singlePipeline));
    }

    @Test
    void collapsesChildrenToUniqueParentsOrderedByInput() {
        // 三个子块命中同属父块 100（seq=0）；输入序即重排序
        parentRows(Map.of(0, parent(100L, 0), 1, parent(100L, 0), 2, parent(100L, 0)));

        MergeResult out = stage.execute(merge(
                child(1L, "pc", 0, 0.95), child(1L, "pc", 1, 0.90), child(1L, "pc", 2, 0.50)), context);

        assertThat(out.chunks()).hasSize(1);
        RetrievedChunk parentHit = out.chunks().get(0);
        assertThat(parentHit.chunkIndex()).isEqualTo(0);
        assertThat(parentHit.text()).isEqualTo("parent-content-100");
        assertThat(parentHit.score()).isEqualTo(0.95);

        ParentFetchOutcome outcome = outcome();
        assertThat(outcome.fetchedParents()).isEqualTo(1);
        assertThat(outcome.mergedFromChildren()).isEqualTo(3);
        assertThat(outcome.degradedToChild()).isZero();
        assertThat(outcome.status()).isEqualTo(StageStatus.SUCCESS);
    }

    @Test
    void appliesChunkAndTokenBudgetPerPipeline() {
        // 管线预算：数量 2、token 100 → 两个 40-token 父块 kept，第三个（100 token）截断
        parentChildPipeline.setParentContextMaxChunks(2);
        parentChildPipeline.setParentContextMaxTokens(100);
        parentRows(Map.of(
                0, parent(100L, 0, shortText(40)),
                1, parent(200L, 1, shortText(40)),
                2, parent(300L, 2, shortText(100))));

        MergeResult out = stage.execute(merge(
                child(1L, "pc", 0, 0.9), child(1L, "pc", 1, 0.8), child(1L, "pc", 2, 0.7)), context);

        assertThat(out.chunks()).hasSize(2);
        assertThat(outcome().truncatedByBudget()).isEqualTo(1);
        assertThat(outcome().detail()).contains("drop");
    }

    @Test
    void degradesToChildWhenParentRowMissing() {
        parentRows(Map.of(0, parent(100L, 0)));
        RetrievedChunk orphan = child(1L, "pc", 9, 0.7);

        MergeResult out = stage.execute(merge(child(1L, "pc", 0, 0.9), orphan), context);

        assertThat(out.chunks()).hasSize(2);
        assertThat(out.chunks().get(1).text()).isEqualTo(orphan.text());
        ParentFetchOutcome outcome = outcome();
        assertThat(outcome.degradedToChild()).isEqualTo(1);
        assertThat(outcome.status()).isEqualTo(StageStatus.DEGRADED);
        assertThat(outcome.reason()).isEqualTo("PARENT_MISSING_DEGRADED_TO_CHILD");
    }

    @Test
    void passesSingleLevelHitsThroughWithoutDegradeCounting() {
        when(chunkService.findParentsByChildSeqIndexes(anyLong(), anyLong(), anyList()))
                .thenReturn(Map.of());

        MergeResult out = stage.execute(merge(child(1L, "single", 0, 0.9)), context);

        assertThat(out.chunks()).hasSize(1);
        assertThat(out.chunks().get(0).chunkIndex()).isEqualTo(0);
        ParentFetchOutcome outcome = outcome();
        assertThat(outcome.degradedToChild()).isZero();
        assertThat(outcome.fetchedParents()).isEqualTo(1);
    }

    @Test
    void emptyInputShortCircuitsWithZeroOutcome() {
        MergeResult in = merge();
        MergeResult out = stage.execute(in, context);

        assertThat(out).isSameAs(in);
        assertThat(outcome().fetchedParents()).isZero();
        verify(chunkService, never()).findParentsByChildSeqIndexes(anyLong(), anyLong(), anyList());
    }

    @Test
    void detailContainsNoTextContent() {
        parentRows(Map.of(0, parent(100L, 0, "机密原文内容不应出现在观测明细中")));
        stage.execute(merge(child(1L, "pc", 0, 0.9)), context);

        String detail = outcome().detail();
        assertThat(detail).contains("keep").doesNotContain("机密原文");
    }

    @Test
    void missingPipelineConfigFallsBackToDefaults() {
        when(pipelineService.findEnabledByTenant(TENANT_ID)).thenReturn(List.of());
        parentRows(Map.of(0, parent(100L, 0)));

        MergeResult out = stage.execute(merge(child(1L, "pc", 0, 0.9)), context);

        assertThat(out.chunks()).hasSize(1);
    }

    private ParentFetchOutcome outcome() {
        return context.get(ParentFetchOutcome.CONTEXT_KEY, ParentFetchOutcome.class);
    }

    private MergeResult merge(RetrievedChunk... chunks) {
        RetrievalRequest request = new RetrievalRequest("问题", null, null, 5, 0.0);
        return new MergeResult(request, List.of(chunks), 1, List.of());
    }

    private void parentRows(Map<Integer, DocumentChunk> byChildSeq) {
        when(chunkService.findParentsByChildSeqIndexes(anyLong(), anyLong(), anyList()))
                .thenReturn(byChildSeq);
    }

    private static RetrievedChunk child(long documentId, String pipelineKey, int chunkIndex, double score) {
        return new RetrievedChunk(documentId, "doc", pipelineKey, null, chunkIndex, score,
                "child-content-" + chunkIndex);
    }

    private static DocumentChunk parent(long id, int seqIndex) {
        return parent(id, seqIndex, "parent-content-" + id);
    }

    private static DocumentChunk parent(long id, int seqIndex, String content) {
        DocumentChunk parent = new DocumentChunk();
        parent.setId(id);
        parent.setSeqIndex(seqIndex);
        parent.setContent(content);
        parent.setChunkLevel("PARENT");
        return parent;
    }

    private static IngestionPipeline pipeline(String key, String strategy, int maxChunks, int maxTokens) {
        IngestionPipeline pipeline = new IngestionPipeline();
        pipeline.setId(key.hashCode() % 1000 + 1L);
        pipeline.setPipelineKey(key);
        pipeline.setStrategy(strategy);
        pipeline.setParentContextMaxChunks(maxChunks);
        pipeline.setParentContextMaxTokens(maxTokens);
        return pipeline;
    }

    private static String shortText(int approxTokens) {
        return "字".repeat(Math.max(1, approxTokens));
    }
}

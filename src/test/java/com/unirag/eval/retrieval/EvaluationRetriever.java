package com.unirag.eval.retrieval;

import com.unirag.eval.dataset.EvaluationDataset;
import com.unirag.eval.dataset.QueryCase;
import com.unirag.eval.dataset.RetrievalConfig;
import com.unirag.retrieval.RetrievalService;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.stage.MergeStage;
import com.unirag.retrieval.stage.QueryEmbeddingStage;
import com.unirag.retrieval.stage.QueryVector;
import com.unirag.retrieval.stage.VectorSearchResult;
import com.unirag.retrieval.stage.VectorSearchStage;
import com.unirag.common.tenant.TenantContext;

import java.util.List;

/** 生产遍 + 诊断遍检索（013 research R4/R5）。 */
public final class EvaluationRetriever {

    private final RetrievalService retrievalService;

    private final QueryEmbeddingStage queryEmbeddingStage;

    private final VectorSearchStage vectorSearchStage;

    private final MergeStage mergeStage;

    private final TenantContext tenantContext;

    public EvaluationRetriever(RetrievalService retrievalService,
                               QueryEmbeddingStage queryEmbeddingStage,
                               VectorSearchStage vectorSearchStage,
                               MergeStage mergeStage,
                               TenantContext tenantContext) {
        this.retrievalService = retrievalService;
        this.queryEmbeddingStage = queryEmbeddingStage;
        this.vectorSearchStage = vectorSearchStage;
        this.mergeStage = mergeStage;
        this.tenantContext = tenantContext;
    }

    public RetrievalOutcome retrieve(EvaluationDataset dataset, long tenantId, QueryCase query) {
        RetrievalConfig config = dataset.config().retrieval();

        RetrievalRequest production = new RetrievalRequest(
                query.question(), null, null,
                config.topK(), config.minScore(), config.recallTopK(),
                config.rerank(), config.rewrite());

        long startedAt = System.nanoTime();
        RetrievalService.RetrievalResult result = TenantScope.inTenant(
                tenantContext, dataset.config().tenant(), tenantId,
                () -> retrievalService.retrieve(production));
        long latencyNanos = System.nanoTime() - startedAt;

        RetrievalRequest diagnostic = new RetrievalRequest(
                query.question(), null, null,
                dataset.config().metrics().diagnosticTopK(), 0.0,
                dataset.config().metrics().diagnosticTopK(), false, config.rewrite());
        StageContext ctx = new StageContext(StageContext.newTraceId(), StageResult.KIND_RETRIEVAL,
                dataset.config().tenant(), tenantId, null, query.question(), null);
        QueryVector queryVector = queryEmbeddingStage.execute(diagnostic, ctx);
        VectorSearchResult tableHits = vectorSearchStage.execute(queryVector, ctx);
        List<RetrievedChunk> merged = mergeStage.execute(tableHits, ctx).chunks();

        return new RetrievalOutcome(result, tableHits, merged, latencyNanos);
    }

    public record RetrievalOutcome(RetrievalService.RetrievalResult production,
                                   VectorSearchResult diagnosticTables,
                                   List<RetrievedChunk> diagnosticMerged,
                                   long latencyNanos) {
    }
}

package com.unirag.retrieval;

import com.unirag.retrieval.model.FailedTable;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.observation.ObservationMetrics;
import com.unirag.retrieval.pipeline.DefaultPipeline;
import com.unirag.retrieval.query.QueryRewriteSupport;
import com.unirag.retrieval.rerank.RerankProperties;
import com.unirag.retrieval.rerank.RerankSupport;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageStatus;
import com.unirag.retrieval.stage.MergeResult;
import com.unirag.retrieval.stage.MergeStage;
import com.unirag.retrieval.stage.QueryEmbeddingStage;
import com.unirag.retrieval.stage.VectorSearchStage;
import com.unirag.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 检索编排入口（US1，FR-001/FR-003）：查询向量化 → 逐表检索 → 归并，全程经阶段流水线执行，
 * 观测与指标在此接入（阶段自身不含任何横切逻辑）。租户一律取自 {@link TenantContext}。
 */
@Service
public class RetrievalService {

    /** 021 章节不存在时的空结果原因标记（FR-007：明确提示，不静默全库检索）。 */
    public static final String EMPTY_REASON_SECTION_NOT_FOUND = "SECTION_NOT_FOUND";

    private final TenantContext tenantContext;
    private final DefaultPipeline pipeline;
    private final ObservationMetrics metrics;
    private final RerankSupport rerankSupport;
    private final RerankProperties rerankProperties;
    private final QueryRewriteSupport rewriteSupport;
    private final com.unirag.infrastructure.persistence.service.DocumentChunkService chunkService;
    private final List<Stage<?, ?>> chain;

    public RetrievalService(TenantContext tenantContext,
                            DefaultPipeline pipeline,
                            ObservationMetrics metrics,
                            RerankSupport rerankSupport,
                            RerankProperties rerankProperties,
                            QueryRewriteSupport rewriteSupport,
                            com.unirag.infrastructure.persistence.service.DocumentChunkService chunkService,
                            QueryEmbeddingStage queryEmbeddingStage,
                            VectorSearchStage vectorSearchStage,
                            MergeStage mergeStage) {
        this.tenantContext = tenantContext;
        this.pipeline = pipeline;
        this.metrics = metrics;
        this.rerankSupport = rerankSupport;
        this.rerankProperties = rerankProperties;
        this.rewriteSupport = rewriteSupport;
        this.chunkService = chunkService;
        this.chain = List.of(queryEmbeddingStage, vectorSearchStage, mergeStage);
    }

    public RetrievalResult retrieve(RetrievalRequest request) {
        String tenantCode = tenantContext.getTenantCode();
        Long tenantId = tenantContext.getTenantId();
        boolean rerank = request.rerank() && rerankSupport.enabledFor(tenantCode);
        boolean rewrite = request.rewrite() && rewriteSupport.enabledFor(tenantCode);
        int recallTopK = rerank
                ? Math.max(request.topK(), rerankProperties.recallTopK())
                : request.topK();
        RetrievalRequest resolved = request.withRerank(rerank, recallTopK).withRewrite(rewrite);
        String traceId = StageContext.newTraceId();
        StageContext ctx = new StageContext(traceId, StageResult.KIND_RETRIEVAL, tenantCode, tenantId,
                null, resolved.query(), resolved.documentId());
        long startedAt = System.nanoTime();
        // 021 FR-007：章节限定先行存在性判定——不存在即明确空结果，不下推向量检索
        if (resolved.section() != null && !chunkService.sectionExists(
                tenantCode, resolved.documentId(), resolved.section())) {
            metrics.recordRetrieval(tenantCode, StageStatus.DEGRADED, elapsed(startedAt));
            return new RetrievalResult(traceId, List.of(), 0, List.of(), EMPTY_REASON_SECTION_NOT_FOUND);
        }
        MergeResult merged;
        try {
            merged = (MergeResult) pipeline.run(chain, resolved, ctx);
        } catch (RuntimeException e) {
            metrics.recordRetrieval(tenantCode, StageStatus.FAILED, elapsed(startedAt));
            throw e;
        }
        merged.failedTables().forEach(failed -> metrics.recordTableFailure(tenantCode));
        metrics.recordRetrieval(tenantCode,
                merged.failedTables().isEmpty() ? StageStatus.SUCCESS : StageStatus.DEGRADED,
                elapsed(startedAt));
        return new RetrievalResult(traceId, merged.chunks(), merged.searchedTables(), merged.failedTables(), null);
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    /**
     * 检索结果（contracts/retrieval-api.md §2）。
     *
     * @param searchedTables 归并前实际检索到的表数
     * @param failedTables   被隔离的单表故障
     * @param emptyReason    021：空结果原因标记（如 SECTION_NOT_FOUND）；正常结果为 {@code null}
     */
    public record RetrievalResult(String traceId,
                                  List<RetrievedChunk> chunks,
                                  int searchedTables,
                                  List<FailedTable> failedTables,
                                  String emptyReason) {

        /** 兼容 021 前调用方：无空结果原因。 */
        public RetrievalResult(String traceId, List<RetrievedChunk> chunks,
                               int searchedTables, List<FailedTable> failedTables) {
            this(traceId, chunks, searchedTables, failedTables, null);
        }
    }
}

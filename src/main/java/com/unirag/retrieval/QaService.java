package com.unirag.retrieval;

import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.observation.ObservationMetrics;
import com.unirag.retrieval.pipeline.DefaultPipeline;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageStatus;
import com.unirag.retrieval.rerank.RerankProperties;
import com.unirag.retrieval.rerank.RerankSupport;
import com.unirag.retrieval.stage.ChatGenerationStage;
import com.unirag.retrieval.stage.MergeStage;
import com.unirag.retrieval.stage.PromptAssemblyStage;
import com.unirag.retrieval.stage.QueryEmbeddingStage;
import com.unirag.retrieval.stage.VectorSearchStage;
import com.unirag.upload.support.TenantContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 问答编排入口（US2，FR-004/FR-005）：五段流水线 —— 查询向量化 → 逐表检索 → 归并 →
 * 提示词组装 → 模型调用。降级语义由阶段承担（检索为空时不调用模型），此处只负责
 * 组装检索请求、生成链路标识并接入指标；租户一律取自 {@link TenantContext}。
 */
@Service
public class QaService {

    private final TenantContext tenantContext;
    private final DefaultPipeline pipeline;
    private final ObservationMetrics metrics;
    private final RerankSupport rerankSupport;
    private final RerankProperties rerankProperties;
    private final List<Stage<?, ?>> chain;

    public QaService(TenantContext tenantContext,
                     DefaultPipeline pipeline,
                     ObservationMetrics metrics,
                     RerankSupport rerankSupport,
                     RerankProperties rerankProperties,
                     QueryEmbeddingStage queryEmbeddingStage,
                     VectorSearchStage vectorSearchStage,
                     MergeStage mergeStage,
                     PromptAssemblyStage promptAssemblyStage,
                     ChatGenerationStage chatGenerationStage) {
        this.tenantContext = tenantContext;
        this.pipeline = pipeline;
        this.metrics = metrics;
        this.rerankSupport = rerankSupport;
        this.rerankProperties = rerankProperties;
        this.chain = List.of(queryEmbeddingStage, vectorSearchStage, mergeStage,
                promptAssemblyStage, chatGenerationStage);
    }

    public QaResult ask(QaRequest request) {
        String tenantCode = tenantContext.getTenantCode();
        Long tenantId = tenantContext.getTenantId();
        String traceId = StageContext.newTraceId();
        StageContext ctx = new StageContext(traceId, StageResult.KIND_QA, tenantCode, tenantId,
                request.sessionId(), request.question(), null);
        // 问题即检索查询；问答不接受单文档过滤（contracts §3）
        boolean rerankIntent = request.rerank() == null || request.rerank();
        boolean rerank = rerankIntent && rerankSupport.enabledFor(tenantCode);
        int recallTopK = rerank
                ? Math.max(request.topK(), rerankProperties.recallTopK())
                : request.topK();
        RetrievalRequest retrievalRequest = new RetrievalRequest(request.question(), request.granularity(),
                null, request.topK(), request.minScore(), recallTopK, rerank);
        long startedAt = System.nanoTime();
        QaAnswer answer;
        try {
            answer = (QaAnswer) pipeline.run(chain, retrievalRequest, ctx);
        } catch (RuntimeException e) {
            metrics.recordQa(tenantCode, StageStatus.FAILED, elapsed(startedAt));
            throw e;
        }
        metrics.recordQa(tenantCode, answer.degraded() ? StageStatus.DEGRADED : StageStatus.SUCCESS,
                elapsed(startedAt));
        return new QaResult(traceId, answer);
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    /** 问答结果（contracts/retrieval-api.md §3）。 */
    public record QaResult(String traceId, QaAnswer answer) {
    }
}

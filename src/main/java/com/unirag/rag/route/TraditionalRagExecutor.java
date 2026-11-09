package com.unirag.rag.route;

import com.unirag.rag.model.RagRequest;
import com.unirag.rag.model.RagResult;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.pipeline.DefaultPipeline;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.query.InheritDecision;
import com.unirag.retrieval.query.QueryRewriteSupport;
import com.unirag.retrieval.rerank.RerankProperties;
import com.unirag.retrieval.rerank.RerankSupport;
import com.unirag.retrieval.stage.ChatGenerationStage;
import com.unirag.retrieval.stage.MergeStage;
import com.unirag.retrieval.stage.PromptAssemblyStage;
import com.unirag.retrieval.stage.QueryEmbeddingStage;
import com.unirag.retrieval.stage.VectorSearchStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 传统 RAG 执行适配器：只承接原问答编排里的六段链组装（020 起 MERGE 与 PROMPT_ASSEMBLY
 * 之间含 PARENT_FETCH 父块回填）与租户开关解析，不改变阶段输入输出、空结果语义或错误语义。
 */
@Slf4j
@Component
public class TraditionalRagExecutor implements RagParadigmExecutor {

    private final DefaultPipeline pipeline;
    private final RerankSupport rerankSupport;
    private final RerankProperties rerankProperties;
    private final QueryRewriteSupport rewriteSupport;
    private final List<Stage<?, ?>> chain;
    private final com.unirag.retrieval.query.CitedDocumentResolver citationResolver;
    private final com.unirag.retrieval.observation.ObservationRecorder observationRecorder;
    private final com.unirag.retrieval.observation.ObservationProperties observationProperties;

    public TraditionalRagExecutor(DefaultPipeline pipeline,
                                  RerankSupport rerankSupport,
                                  RerankProperties rerankProperties,
                                  QueryRewriteSupport rewriteSupport,
                                  QueryEmbeddingStage queryEmbeddingStage,
                                  VectorSearchStage vectorSearchStage,
                                  MergeStage mergeStage,
                                  com.unirag.retrieval.stage.ParentFetchStage parentFetchStage,
                                  PromptAssemblyStage promptAssemblyStage,
                                  ChatGenerationStage chatGenerationStage,
                                  com.unirag.retrieval.query.CitedDocumentResolver citationResolver,
                                  com.unirag.retrieval.observation.ObservationRecorder observationRecorder,
                                  com.unirag.retrieval.observation.ObservationProperties observationProperties) {
        this.pipeline = pipeline;
        this.rerankSupport = rerankSupport;
        this.rerankProperties = rerankProperties;
        this.rewriteSupport = rewriteSupport;
        this.citationResolver = citationResolver;
        this.observationRecorder = observationRecorder;
        this.observationProperties = observationProperties;
        // 020：MERGE（含 RERANK 扩展）之后回填父块，PROMPT_ASSEMBLY 之前；
        // 纯检索服务链（RetrievalService）不含此阶段（contracts/parent-child-chunking.md §2）
        this.chain = List.of(queryEmbeddingStage, vectorSearchStage, mergeStage,
                parentFetchStage, promptAssemblyStage, chatGenerationStage);
    }

    @Override
    public RagParadigm paradigm() {
        return RagParadigm.TRADITIONAL_RAG;
    }

    @Override
    public boolean enabledFor(String tenantCode) {
        return true;
    }

    @Override
    public boolean healthyFor(String tenantCode) {
        return true;
    }

    @Override
    public RagResult execute(RagRequest request, StageContext ctx) {
        String tenantCode = ctx.tenantCode();
        boolean rerankIntent = request.rerank() == null || request.rerank();
        boolean rerank = rerankIntent && rerankSupport.enabledFor(tenantCode);
        boolean rewriteIntent = request.rewrite() == null || request.rewrite();
        boolean rewrite = rewriteIntent && rewriteSupport.enabledFor(tenantCode);
        int recallTopK = rerank
                ? Math.max(request.topK(), rerankProperties.recallTopK())
                : request.topK();
        // 023：指代型追问继承上一轮引用最多的文档（fail-open，决策入上下文供观测）
        InheritDecision inheritDecision = citationResolver.resolve(
                ctx.tenantId(), request.sessionId(), request.question());
        ctx.put(com.unirag.retrieval.query.CitedDocumentResolver.CONTEXT_KEY, inheritDecision);
        log.debug("[retrieval] trace={} CITATION_INHERIT target={} matched={}",
                ctx.traceId(), inheritDecision.reason(),
                inheritDecision.documentId() == null ? "-" : "INHERITED:" + inheritDecision.documentId());
        // 023 FR-006：判定留痕（复用观测表通用列；观测关闭时跳过，recorder 内部 try/catch 降级）
        if (observationProperties.isEnabled()) {
            observationRecorder.record(ctx, new com.unirag.retrieval.model.StageResult(
                    com.unirag.retrieval.pipeline.StageName.CITATION_INHERIT,
                    inheritDecision.documentId() == null
                            ? inheritDecision.reason().name()
                            : "INHERITED:" + inheritDecision.documentId(),
                    inheritDecision.reason() == InheritDecision.Reason.RESOLVER_ERROR
                            ? com.unirag.retrieval.pipeline.StageStatus.DEGRADED
                            : com.unirag.retrieval.pipeline.StageStatus.SUCCESS,
                    null, null, null, null, null));
        }
        Long inheritedDocumentId = inheritDecision.reason() == InheritDecision.Reason.INHERITED
                ? inheritDecision.documentId()
                : null;
        RetrievalRequest retrievalRequest = new RetrievalRequest(request.question(), request.granularity(),
                inheritedDocumentId, request.topK(), request.minScore(), recallTopK, rerank, rewrite);
        return RagResult.from((QaAnswer) pipeline.run(chain, retrievalRequest, ctx));
    }
}

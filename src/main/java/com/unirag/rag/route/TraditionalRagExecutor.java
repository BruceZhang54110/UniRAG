package com.unirag.rag.route;

import com.unirag.rag.model.RagRequest;
import com.unirag.rag.model.RagResult;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.pipeline.DefaultPipeline;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.query.QueryRewriteSupport;
import com.unirag.retrieval.rerank.RerankProperties;
import com.unirag.retrieval.rerank.RerankSupport;
import com.unirag.retrieval.stage.ChatGenerationStage;
import com.unirag.retrieval.stage.MergeStage;
import com.unirag.retrieval.stage.PromptAssemblyStage;
import com.unirag.retrieval.stage.QueryEmbeddingStage;
import com.unirag.retrieval.stage.VectorSearchStage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 传统 RAG 执行适配器：只承接原问答编排里的五段链组装与租户开关解析，
 * 不改变阶段输入输出、空结果语义或错误语义。
 */
@Component
public class TraditionalRagExecutor implements RagParadigmExecutor {

    private final DefaultPipeline pipeline;
    private final RerankSupport rerankSupport;
    private final RerankProperties rerankProperties;
    private final QueryRewriteSupport rewriteSupport;
    private final List<Stage<?, ?>> chain;

    public TraditionalRagExecutor(DefaultPipeline pipeline,
                                  RerankSupport rerankSupport,
                                  RerankProperties rerankProperties,
                                  QueryRewriteSupport rewriteSupport,
                                  QueryEmbeddingStage queryEmbeddingStage,
                                  VectorSearchStage vectorSearchStage,
                                  MergeStage mergeStage,
                                  PromptAssemblyStage promptAssemblyStage,
                                  ChatGenerationStage chatGenerationStage) {
        this.pipeline = pipeline;
        this.rerankSupport = rerankSupport;
        this.rerankProperties = rerankProperties;
        this.rewriteSupport = rewriteSupport;
        this.chain = List.of(queryEmbeddingStage, vectorSearchStage, mergeStage,
                promptAssemblyStage, chatGenerationStage);
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
        RetrievalRequest retrievalRequest = new RetrievalRequest(request.question(), request.granularity(),
                null, request.topK(), request.minScore(), recallTopK, rerank, rewrite);
        return RagResult.from((QaAnswer) pipeline.run(chain, retrievalRequest, ctx));
    }
}

package com.unirag.retrieval.stage;

import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.TargetResolver;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.TargetTable;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import com.unirag.upload.support.ApiException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询向量化（FR-001）：先解析目标表，再经 {@link TenantResourceRouter} 取各表所属管线的
 * EMBEDDING_MODEL 把查询向量化。同一租户不同管线可配不同嵌入模型，故按模型分别向量化。
 */
@Component
public class QueryEmbeddingStage implements Stage<RetrievalRequest, QueryVector> {

    private final TenantResourceRouter router;
    private final TargetResolver targetResolver;

    public QueryEmbeddingStage(TenantResourceRouter router, TargetResolver targetResolver) {
        this.router = router;
        this.targetResolver = targetResolver;
    }

    @Override
    public StageName name() {
        return StageName.QUERY_EMBEDDING;
    }

    @Override
    public QueryVector execute(RetrievalRequest request, StageContext ctx) {
        List<TargetTable> targets = targetResolver.resolve(ctx.tenantId(), request.granularity());
        Map<String, Embedding> embeddings = new LinkedHashMap<>();
        for (TargetTable target : targets) {
            embeddings.computeIfAbsent(target.modelKey(), modelKey -> embed(ctx, modelKey, request.query()));
        }
        return new QueryVector(request, targets, embeddings);
    }

    /**
     * 无嵌入模型资源（未配置或配置失效）与调用失败同属"模型不可用"，统一给可读失败原因（FR-005），
     * 避免 TenantResourceNotFoundException 直接冒泡成 500。
     */
    private Embedding embed(StageContext ctx, String modelKey, String query) {
        try {
            EmbeddingModel model = router.get(ctx.tenantCode(), ResourceType.EMBEDDING_MODEL, modelKey,
                    EmbeddingModel.class);
            return model.embed(query).content();
        } catch (RuntimeException e) {
            throw new ApiException("EMBEDDING_MODEL_UNAVAILABLE", "租户嵌入模型不可用：" + Redaction.describe(e));
        }
    }
}

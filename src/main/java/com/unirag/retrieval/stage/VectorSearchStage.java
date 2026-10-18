package com.unirag.retrieval.stage;

import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.model.FailedTable;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.TargetTable;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐表相似度检索（FR-001/FR-002/FR-009）：按目标表逐个经 Router 取 EMBEDDING_STORE 检索。
 *
 * <p>关键约束（research.md R5/R7）：元数据过滤的数值键必须传 {@code Long}/{@code Integer}，
 * 否则拼出 {@code (metadata->>'k')::null} 导致 SQL 语法错误；{@code maxResults}/{@code minScore}
 * 都在 SQL 内生效，返回条数可能少于 topK —— 这是正确行为，不做补齐。单表失败只记入失败清单，
 * 其余表结果照常返回。
 */
@Component
public class VectorSearchStage implements Stage<QueryVector, VectorSearchResult> {

    static final String META_DOCUMENT_ID = "document_id";

    static final String META_PIPELINE_KEY = "pipeline_key";

    private final TenantResourceRouter router;

    public VectorSearchStage(TenantResourceRouter router) {
        this.router = router;
    }

    @Override
    public StageName name() {
        return StageName.VECTOR_SEARCH;
    }

    @Override
    public VectorSearchResult execute(QueryVector query, StageContext ctx) {
        List<TableHits> hits = new ArrayList<>();
        List<FailedTable> failedTables = new ArrayList<>();
        for (TargetTable target : query.targets()) {
            try {
                hits.add(search(target, query, ctx));
            } catch (Exception e) {
                failedTables.add(new FailedTable(target.storeKey(), Redaction.describe(e)));
            }
        }
        return new VectorSearchResult(query, hits, failedTables);
    }

    @SuppressWarnings("unchecked")
    private TableHits search(TargetTable target, QueryVector query, StageContext ctx) {
        EmbeddingStore<TextSegment> store = router.get(ctx.tenantCode(), ResourceType.EMBEDDING_STORE,
                target.storeKey(), EmbeddingStore.class);
        Embedding embedding = query.embeddings().get(target.modelKey());
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(embedding)
                .maxResults(query.request().topK())
                .minScore(query.request().minScore())
                .filter(filterFor(query.request(), target))
                .build();
        return new TableHits(target.storeKey(), target.pipelineKey(), store.search(searchRequest).matches());
    }

    /** 过滤以向量库原生能力执行（FR-002）：文档标识按请求给，粒度仅在其被显式指定时施加。 */
    private static Filter filterFor(RetrievalRequest request, TargetTable target) {
        Filter filter = request.documentId() == null
                ? null
                : new IsEqualTo(META_DOCUMENT_ID, request.documentId());
        if (request.granularity() == null) {
            return filter;
        }
        Filter pipelineFilter = new IsEqualTo(META_PIPELINE_KEY, target.pipelineKey());
        return filter == null ? pipelineFilter : filter.and(pipelineFilter);
    }
}

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
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
public class VectorSearchStage implements Stage<QueryVector, VectorSearchResult> {

    static final String META_DOCUMENT_ID = "document_id";

    static final String META_DOC_NAME = "doc_name";

    static final String META_PIPELINE_KEY = "pipeline_key";

    static final String META_CHUNK_INDEX = "chunk_index";

    /** 日志里片段文本的展示上限，避免整段原文刷屏。 */
    private static final int LOG_TEXT_PREVIEW = 200;

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
                String reason = Redaction.describe(e);
                log.debug("[retrieval] trace={} VECTOR_SEARCH table={} pipeline={} failed: {}",
                        ctx.traceId(), target.storeKey(), target.pipelineKey(), reason);
                failedTables.add(new FailedTable(target.storeKey(), reason));
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
                .maxResults(query.request().recallTopK())
                .minScore(query.request().minScore())
                .filter(filterFor(query.request(), target))
                .build();
        List<EmbeddingMatch<TextSegment>> matches = store.search(searchRequest).matches();
        log.debug("[retrieval] trace={} VECTOR_SEARCH table={} pipeline={} maxResults={} minScore={} hits={}",
                ctx.traceId(), target.storeKey(), target.pipelineKey(),
                query.request().recallTopK(), query.request().minScore(), matches.size());
        if (log.isDebugEnabled()) {
            for (EmbeddingMatch<TextSegment> match : matches) {
                Metadata metadata = match.embedded().metadata();
                log.debug("[retrieval] trace={} VECTOR_SEARCH hit score={} docId={} docName={} pipeline={} chunkIndex={} text={}",
                        ctx.traceId(), formatScore(match.score()),
                        metadata.getLong(META_DOCUMENT_ID), metadata.getString(META_DOC_NAME),
                        target.pipelineKey(), metadata.getInteger(META_CHUNK_INDEX),
                        preview(match.embedded().text()));
            }
        }
        return new TableHits(target.storeKey(), target.pipelineKey(), matches);
    }

    private static String formatScore(double score) {
        return String.format("%.4f", score);
    }

    /** 归一空白并截断，日志可读且不会因长片段刷屏。 */
    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.strip().replaceAll("\\s+", " ");
        return normalized.length() <= LOG_TEXT_PREVIEW
                ? normalized
                : normalized.substring(0, LOG_TEXT_PREVIEW) + "…";
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

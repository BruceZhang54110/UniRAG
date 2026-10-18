package com.unirag.retrieval.stage;

import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 跨表确定性归并（FR-003，research.md R4）：按 score 降序、同分以
 * {@code (documentId, pipelineKey, chunkIndex)} 升序兜底，截断到全局 topK，不做跨表去重
 * —— 粗/细粒度片段文本本就不同，"粗排定位、细排佐证"需要两者并存。
 */
@Slf4j
@Component
public class MergeStage implements Stage<VectorSearchResult, MergeResult> {

    static final String META_DOCUMENT_ID = "document_id";

    static final String META_DOC_NAME = "doc_name";

    static final String META_PIPELINE_KEY = "pipeline_key";

    static final String META_CHUNK_INDEX = "chunk_index";

    static final String META_DOCUMENT_VERSION_ID = "document_version_id";

    private static final Comparator<RetrievedChunk> ORDER = Comparator
            .comparingDouble(RetrievedChunk::score).reversed()
            .thenComparingLong(RetrievedChunk::documentId)
            .thenComparing(RetrievedChunk::pipelineKey)
            .thenComparingInt(RetrievedChunk::chunkIndex);

    @Override
    public StageName name() {
        return StageName.MERGE;
    }

    @Override
    public MergeResult execute(VectorSearchResult search, StageContext ctx) {
        List<RetrievedChunk> chunks = search.hits().stream()
                .flatMap(hits -> hits.matches().stream().map(match -> toChunk(hits, match)))
                .filter(Objects::nonNull)
                .sorted(ORDER)
                .limit(search.query().request().recallTopK())
                .toList();
        if (log.isDebugEnabled()) {
            log.debug("[retrieval] trace={} MERGE topK={} recallTopK={} searchedTables={} failedTables={} merged={}",
                    ctx.traceId(), search.query().request().topK(), search.query().request().recallTopK(),
                    search.searchedTables(), search.failedTables().size(), chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk chunk = chunks.get(i);
                log.debug("[retrieval] trace={} MERGE rank={} score={} docId={} docName={} pipeline={} chunkIndex={}",
                        ctx.traceId(), i + 1, String.format("%.4f", chunk.score()), chunk.documentId(),
                        chunk.docName(), chunk.pipelineKey(), chunk.chunkIndex());
            }
        }
        return new MergeResult(search.query().request(), chunks, search.searchedTables(),
                search.failedTables());
    }

    private static RetrievedChunk toChunk(TableHits hits, EmbeddingMatch<TextSegment> match) {
        Metadata metadata = match.embedded().metadata();
        Long documentId = metadata.getLong(META_DOCUMENT_ID);
        Integer chunkIndex = metadata.getInteger(META_CHUNK_INDEX);
        if (documentId == null || chunkIndex == null) {
            // metadata 来自向量表的 JSON 列，可能被外部写入污染；缺回链键的行跳过，不拖垮整次检索
            return null;
        }
        return new RetrievedChunk(documentId,
                metadata.getString(META_DOC_NAME),
                hits.pipelineKey(),
                metadata.getLong(META_DOCUMENT_VERSION_ID),
                chunkIndex,
                match.score(),
                match.embedded().text());
    }
}

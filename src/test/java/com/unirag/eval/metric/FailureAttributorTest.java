package com.unirag.eval.metric;

import com.unirag.eval.dataset.QueryCase;
import com.unirag.eval.dataset.RelevanceLabel;
import com.unirag.eval.dataset.RetrievalConfig;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.model.TargetTable;
import com.unirag.retrieval.stage.QueryVector;
import com.unirag.retrieval.stage.TableHits;
import com.unirag.retrieval.stage.VectorSearchResult;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FailureAttributorTest {

    private final FailureAttributor attributor = new FailureAttributor();

    private final QueryCase query = new QueryCase("q-1", "问题？", List.of("fact"), "dev", true,
            List.of(new RelevanceLabel("doc.md", "目标片段")));

    private final List<FailureAttributor.ChunkView> chunks =
            List.of(new FailureAttributor.ChunkView("doc.md", "包含目标片段"));

    @Test
    void noAttributionWhenProductionHits() {
        Attribution result = attributor.attribute(query, List.of(chunk("目标片段", 0.9)),
                diagnosticTables(true), List.of(chunk("目标片段", 0.9)),
                chunks, config(2, 0.5, false));
        assertFalse(result.isFailure());
    }

    @Test
    void detectsChunkingGap() {
        Attribution result = attributor.attribute(query, List.of(), diagnosticTables(false), List.of(),
                List.of(), config(2, 0.5, false));
        assertEquals(FailureCategory.CHUNKING_GAP, result.category());
    }

    @Test
    void detectsNotRecalled() {
        Attribution result = attributor.attribute(query, List.of(), diagnosticTables(false),
                List.of(chunk("目标片段", 0.9)), chunks, config(2, 0.5, false));
        assertEquals(FailureCategory.NOT_RECALLED, result.category());
    }

    @Test
    void detectsMergeDrop() {
        Attribution result = attributor.attribute(query, List.of(), diagnosticTables(true),
                List.of(), chunks, config(2, 0.5, false));
        assertEquals(FailureCategory.MERGE_OR_TOPK_DROPPED, result.category());
    }

    @Test
    void detectsThresholdFiltered() {
        Attribution result = attributor.attribute(query, List.of(), diagnosticTables(true),
                List.of(chunk("目标片段", 0.1)), chunks, config(2, 0.5, false));
        assertEquals(FailureCategory.THRESHOLD_FILTERED, result.category());
    }

    @Test
    void detectsRerankDropped() {
        Attribution result = attributor.attribute(query, List.of(), diagnosticTables(true),
                List.of(chunk("目标片段", 0.9)), chunks, config(2, 0.5, true));
        assertEquals(FailureCategory.RERANK_DROPPED, result.category());
    }

    @Test
    void detectsBelowTopK() {
        Attribution result = attributor.attribute(query, List.of(), diagnosticTables(true),
                List.of(chunk("无关", 0.9), chunk("无关", 0.8), chunk("目标片段", 0.7)),
                chunks, config(2, 0.5, false));
        assertEquals(FailureCategory.BELOW_TOPK, result.category());
    }

    private static RetrievalConfig config(int topK, double minScore, boolean rerank) {
        return new RetrievalConfig(topK, 5, minScore, rerank, false);
    }

    private static RetrievedChunk chunk(String text, double score) {
        return new RetrievedChunk(1L, "doc.md", "fine", null, 0, score, text);
    }

    private static VectorSearchResult diagnosticTables(boolean hit) {
        RetrievalRequest request = new RetrievalRequest("问题？", null, null, 10, 0.0, 10, false, false);
        QueryVector queryVector = new QueryVector(request,
                List.of(new TargetTable("fine", "store", "model")), Map.of());
        if (!hit) {
            return new VectorSearchResult(queryVector, List.of(), List.of());
        }
        Metadata metadata = new Metadata();
        metadata.put("doc_name", "doc.md");
        metadata.put("chunk_index", 0);
        TextSegment segment = TextSegment.from("包含目标片段", metadata);
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.9, "id",
                Embedding.from(new float[]{1.0f}), segment);
        return new VectorSearchResult(queryVector,
                List.of(new TableHits("store", "fine", List.of(match))), List.of());
    }
}

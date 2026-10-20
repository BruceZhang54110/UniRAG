package com.unirag.eval.metric;

import com.unirag.eval.dataset.QueryCase;
import com.unirag.eval.dataset.RelevanceLabel;
import com.unirag.retrieval.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiPipelineMetricsTest {

    @Test
    void deduplicatesDocumentsButKeepsChunkEvidence() {
        QueryCase query = new QueryCase("q-1", "问题？", List.of("fact"), "dev", true,
                List.of(new RelevanceLabel("doc.md", "目标片段")));
        List<RetrievedChunk> retrieved = List.of(
                new RetrievedChunk(1L, "doc.md", "coarse", null, 0, 0.9, "粗粒度：目标片段"),
                new RetrievedChunk(1L, "doc.md", "fine", null, 0, 0.8, "细粒度：目标片段"));

        Map<String, Double> metrics = new RetrievalMetrics()
                .perQuery(query, retrieved, List.of(1, 3), new RelevanceMatcher());

        assertEquals(1.0, metrics.get("documentRecall@3"), 1e-9);
        assertEquals(1.0, metrics.get("chunkRecall@3"), 1e-9);
        assertEquals(1.0, metrics.get("hitRate@3"), 1e-9);
    }
}

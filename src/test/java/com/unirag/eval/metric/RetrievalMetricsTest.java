package com.unirag.eval.metric;

import com.unirag.eval.dataset.MetricConfig;
import com.unirag.eval.dataset.QueryCase;
import com.unirag.eval.dataset.RelevanceLabel;
import com.unirag.retrieval.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalMetricsTest {

    private final RetrievalMetrics metrics = new RetrievalMetrics();

    private final RelevanceMatcher matcher = new RelevanceMatcher();

    private final MetricConfig config = new MetricConfig(List.of(1, 3), 10);

    @Test
    void computesDocumentAndChunkMetrics() {
        QueryCase query = query(List.of(
                new RelevanceLabel("docB.md", "目标一"),
                new RelevanceLabel("docB.md", "目标二")));
        List<RetrievedChunk> retrieved = List.of(
                chunk("docA.md", "无关内容", 0.9),
                chunk("docB.md", "这里包含目标一", 0.8),
                chunk("docB.md", "这里包含目标二", 0.7));

        Map<String, Double> result = metrics.perQuery(query, retrieved, config.k(), matcher);

        assertEquals(0.0, result.get("documentRecall@1"), 1e-9);
        assertEquals(1.0, result.get("documentRecall@3"), 1e-9);
        assertEquals(0.0, result.get("chunkRecall@1"), 1e-9);
        assertEquals(1.0, result.get("chunkRecall@3"), 1e-9);
        assertEquals(1.0, result.get("hitRate@3"), 1e-9);
        assertEquals(0.5, result.get("mrr@3"), 1e-9);
        assertEquals(0.6934, result.get("ndcg@3"), 1e-3);
    }

    @Test
    void computesUnanswerableFalsePositive() {
        QueryCase query = new QueryCase("q-u", "无法回答？", List.of("unanswerable"),
                "test", false, List.of());
        assertEquals(0.0, metrics.perQuery(query, List.of(), config.k(), matcher)
                .get(MetricKeys.UNANSWERABLE_FALSE_POSITIVE_RATE), 1e-9);
        assertEquals(1.0, metrics.perQuery(query, List.of(chunk("docA.md", "无关", 0.5)),
                config.k(), matcher).get(MetricKeys.UNANSWERABLE_FALSE_POSITIVE_RATE), 1e-9);
    }

    @Test
    void aggregatesOverallAndTags() {
        QueryCase answerable = query(List.of(new RelevanceLabel("docB.md", "目标一")));
        QueryCase unanswerable = new QueryCase("q-u", "无法回答？", List.of("unanswerable"),
                "test", false, List.of());

        List<QueryOutcome> outcomes = List.of(
                outcome(answerable, List.of(chunk("docB.md", "目标一", 0.9)), 100_000_000L),
                outcome(unanswerable, List.of(), 300_000_000L));

        Map<String, Double> overall = metrics.aggregateOverall(outcomes, config);
        assertEquals(1.0, overall.get("chunkRecall@3"), 1e-9);
        assertEquals(0.0, overall.get(MetricKeys.UNANSWERABLE_FALSE_POSITIVE_RATE), 1e-9);
        assertEquals(100.0, overall.get(MetricKeys.LATENCY_P50), 1e-9);
        assertTrue(overall.containsKey(MetricKeys.LATENCY_P95));

        Map<String, Map<String, Double>> byTag = metrics.aggregateByTag(outcomes, config);
        assertEquals(1.0, byTag.get("fact").get("chunkRecall@3"), 1e-9);
        assertEquals(0.0, byTag.get("unanswerable").get(MetricKeys.UNANSWERABLE_FALSE_POSITIVE_RATE), 1e-9);
    }

    private static QueryOutcome outcome(QueryCase query, List<RetrievedChunk> retrieved, long latencyNanos) {
        return new QueryOutcome(query, retrieved, null, List.of(),
                new RetrievalMetrics().perQuery(query, retrieved, List.of(1, 3), new RelevanceMatcher()),
                latencyNanos, Attribution.none());
    }

    private static QueryCase query(List<RelevanceLabel> labels) {
        return new QueryCase("q-1", "问题？", List.of("fact"), "dev", true, labels);
    }

    private static RetrievedChunk chunk(String doc, String text, double score) {
        return new RetrievedChunk(1L, doc, "fine", null, 0, score, text);
    }
}

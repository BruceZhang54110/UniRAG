package com.unirag.eval.dataset;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetValidatorTest {

    private final DatasetValidator validator = new DatasetValidator();

    @Test
    void acceptsValidDataset() {
        assertDoesNotThrow(() -> validator.validate(dataset(query("q-1", true, "目标片段")), "baseline"));
    }

    @Test
    void rejectsDuplicateQueryId() {
        EvaluationDataset dataset = dataset(
                query("q-1", true, "目标片段"),
                query("q-1", true, "目标片段"));
        assertRule("V-003", dataset);
    }

    @Test
    void rejectsMissingDocument() {
        EvaluationDataset dataset = dataset(queryWith("q-1", "missing.md", "目标片段"));
        assertRule("V-005", dataset);
    }

    @Test
    void rejectsMissingSpan() {
        EvaluationDataset dataset = dataset(queryWith("q-1", "doc.md", "不存在的片段"));
        assertRule("V-005", dataset);
    }

    @Test
    void rejectsAnswerableWithoutLabels() {
        EvaluationDataset dataset = dataset(new QueryCase("q-1", "问题？", List.of("fact"), "dev", true, List.of()));
        assertRule("V-004", dataset);
    }

    @Test
    void rejectsUnanswerableWithLabels() {
        EvaluationDataset dataset = dataset(new QueryCase("q-1", "问题？", List.of("fact"), "dev", false,
                List.of(new RelevanceLabel("doc.md", "目标片段"))));
        assertRule("V-004", dataset);
    }

    @Test
    void rejectsNonEvalTenant() {
        DatasetConfig config = config("baseline", "tenant", 5, 20, 0.2);
        EvaluationDataset dataset = new EvaluationDataset("baseline", corpus(),
                List.of(query("q-1", true, "目标片段")), config, Map.of(), "h", "h", "h", "v");
        assertRule("V-007", dataset);
    }

    @Test
    void rejectsInvalidTopK() {
        DatasetConfig config = config("baseline", "eval_baseline", 0, 20, 0.2);
        EvaluationDataset dataset = new EvaluationDataset("baseline", corpus(),
                List.of(query("q-1", true, "目标片段")), config, Map.of(), "h", "h", "h", "v");
        assertRule("V-006", dataset);
    }

    @Test
    void rejectsUnknownThresholdMetric() {
        EvaluationDataset dataset = new EvaluationDataset("baseline", corpus(),
                List.of(query("q-1", true, "目标片段")), config("baseline", "eval_baseline", 5, 20, 0.2),
                Map.of("unknown", new ThresholdRule(0.5, null)), "h", "h", "h", "v");
        assertRule("V-011", dataset);
    }

    @Test
    void rejectsInvalidPipelineOverlap() {
        DatasetConfig config = new DatasetConfig("baseline", "eval_baseline",
                new StoreConfig("embeddings_eval_baseline", 1024),
                List.of(new PipelineConfig("fine", 100, 100, "eval-embedding", "eval-store")),
                new RetrievalConfig(5, 20, 0.2, false, false),
                new MetricConfig(List.of(1, 3, 5, 10), 50));
        EvaluationDataset dataset = new EvaluationDataset("baseline", corpus(),
                List.of(query("q-1", true, "目标片段")), config, Map.of(), "h", "h", "h", "v");
        assertRule("V-008", dataset);
    }

    @Test
    void rejectsNameMismatch() {
        EvaluationDataset dataset = new EvaluationDataset("other", corpus(),
                List.of(query("q-1", true, "目标片段")),
                config("other", "eval_baseline", 5, 20, 0.2), Map.of(), "h", "h", "h", "v");
        assertRule("V-001", dataset);
    }

    @Test
    void rejectsDuplicateCorpusDocument() {
        List<CorpusDocument> duplicate = List.of(
                new CorpusDocument("doc.md", "doc.md", "内容", "doc.md"),
                new CorpusDocument("doc.md", "doc.md", "内容", "doc.md"));
        EvaluationDataset dataset = new EvaluationDataset("baseline", duplicate,
                List.of(query("q-1", true, "目标片段")),
                config("baseline", "eval_baseline", 5, 20, 0.2), Map.of(), "h", "h", "h", "v");
        assertRule("V-002", dataset);
    }

    @Test
    void rejectsEmptyCorpus() {
        EvaluationDataset dataset = new EvaluationDataset("baseline", List.of(),
                List.of(query("q-1", true, "目标片段")),
                config("baseline", "eval_baseline", 5, 20, 0.2), Map.of(), "h", "h", "h", "v");
        assertRule("V-002", dataset);
    }

    private void assertRule(String rule, EvaluationDataset dataset) {
        DatasetValidationException error = assertThrows(DatasetValidationException.class,
                () -> validator.validate(dataset, "baseline"));
        assertTrue(error.getMessage().contains(rule), error.getMessage());
    }

    private static EvaluationDataset dataset(QueryCase... queries) {
        return new EvaluationDataset("baseline", corpus(), List.of(queries),
                config("baseline", "eval_baseline", 5, 20, 0.2), Map.of(), "h", "h", "h", "v");
    }

    private static DatasetConfig config(String name, String tenant, int topK, int recallTopK, double minScore) {
        return new DatasetConfig(name, tenant,
                new StoreConfig("embeddings_eval_baseline", 1024),
                List.of(new PipelineConfig("fine", 300, 50, "eval-embedding", "eval-store")),
                new RetrievalConfig(topK, recallTopK, minScore, false, false),
                new MetricConfig(List.of(1, 3, 5, 10), 50));
    }

    private static List<CorpusDocument> corpus() {
        return List.of(new CorpusDocument("doc.md", "doc.md", "这是测试内容，包含目标片段。", "doc.md"));
    }

    private static QueryCase query(String id, boolean answerable, String span) {
        return queryWith(id, "doc.md", span);
    }

    private static QueryCase queryWith(String id, String doc, String span) {
        return new QueryCase(id, "问题？", List.of("fact"), "dev", true,
                List.of(new RelevanceLabel(doc, span)));
    }
}

package com.unirag.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.eval.RunMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportRedactionTest {

    @TempDir
    Path tempDir;

    @Test
    void reportAndMarkdownContainNoCredentials() throws Exception {
        EvaluationReport report = new EvaluationReport(
                1,
                new EvaluationReport.RunInfo("run-1", RunMode.REPLAY,
                        EvaluationReport.RunStatus.SUCCESS, "start", "end", null),
                new EvaluationReport.DatasetSnapshot("sample", "v1", "c", "q", 1, 1, 1, 0),
                new EvaluationReport.ConfigSnapshot(
                        "abc1234",
                        new EvaluationReport.EmbeddingSnapshot("deterministic-test", 8),
                        List.of(new EvaluationReport.PipelineSnapshot("fine", 64, 8,
                                "eval-embedding", "eval-store")),
                        new EvaluationReport.RetrievalSnapshot(3, 5, 0.0, false, false),
                        new EvaluationReport.MetricsSnapshot(List.of(1, 3), 10),
                        Map.of()),
                Map.of("chunkRecall@3", 1.0),
                Map.of("fact", Map.of("chunkRecall@3", 1.0)),
                List.of(),
                Map.of());

        Path json = new ReportWriter(new ObjectMapper()).write(report, tempDir);
        Path markdown = json.resolveSibling("run-1.md");
        String text = Files.readString(json) + Files.readString(markdown);

        assertFalse(text.contains("EVAL_EMBEDDING_API_KEY"));
        assertFalse(text.contains("api-key"));
        assertFalse(text.contains("password"));
        assertFalse(text.contains("sk-secret"));
        assertTrue(text.contains("deterministic-test"));
    }
}

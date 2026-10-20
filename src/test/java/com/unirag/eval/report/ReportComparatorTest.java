package com.unirag.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportComparatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void comparesCompatibleReportsAndMarksRegressions() throws Exception {
        Path baseline = report("baseline.json", "b", "m", 0.72, 0.0, 0.80);
        Path candidate = report("candidate.json", "c", "m", 0.65, 0.15, 0.80);

        ReportComparator.ComparisonResult result = new ReportComparator(mapper).compare(baseline, candidate);

        assertTrue(result.comparable());
        assertTrue(result.differences().isEmpty());
        ReportComparator.MetricDelta chunkRecall = metric(result, "chunkRecall@3");
        assertEquals(-0.07, chunkRecall.absoluteDelta(), 1e-9);
        assertEquals(-0.07 / 0.72, chunkRecall.relativeDelta(), 1e-9);
        assertFalse(chunkRecall.passed());
        assertTrue(chunkRecall.regressed());

        ReportComparator.MetricDelta falsePositive = metric(result, "unanswerableFalsePositiveRate");
        assertFalse(falsePositive.passed());
        assertTrue(falsePositive.regressed());

        assertEquals(2, result.summary().thresholdFailed());
        assertEquals(2, result.summary().regressed());
    }

    @Test
    void refusesIncompatibleModel() throws Exception {
        Path baseline = report("baseline.json", "b", "m1", 0.72, 0.0, 0.80);
        Path candidate = report("candidate.json", "c", "m2", 0.72, 0.0, 0.80);

        ReportComparator.ComparisonResult result = new ReportComparator(mapper).compare(baseline, candidate);

        assertFalse(result.comparable());
        assertTrue(result.metrics().isEmpty());
        assertTrue(result.differences().stream()
                .anyMatch(difference -> difference.field().equals("config.embedding.model")));
    }

    private ReportComparator.MetricDelta metric(ReportComparator.ComparisonResult result, String name) {
        return result.metrics().stream().filter(delta -> delta.name().equals(name)).findFirst().orElseThrow();
    }

    private Path report(String fileName, String runId, String model, double chunkRecall,
                        double falsePositive, double tagRecall) throws Exception {
        String json = """
                {
                  "reportVersion": 1,
                  "run": {"id": "%s", "mode": "replay", "status": "SUCCESS"},
                  "dataset": {"version": "v1"},
                  "config": {
                    "embedding": {"model": "%s", "dimension": 8},
                    "pipelines": [{"key": "fine", "maxSegmentTokens": 64, "overlapTokens": 8}],
                    "retrieval": {"topK": 3, "recallTopK": 5, "minScore": 0.0,
                                  "rerank": false, "rewrite": false},
                    "metrics": {"k": [1, 3], "diagnosticTopK": 10},
                    "thresholds": {
                      "chunkRecall@3": {"min": 0.7},
                      "unanswerableFalsePositiveRate": {"max": 0.1}
                    }
                  },
                  "overall": {"chunkRecall@3": %s, "unanswerableFalsePositiveRate": %s},
                  "byTag": {"fact": {"chunkRecall@3": %s}},
                  "queries": [],
                  "failures": {}
                }
                """.formatted(runId, model, chunkRecall, falsePositive, tagRecall);
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, json);
        return path;
    }
}

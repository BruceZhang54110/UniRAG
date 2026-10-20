package com.unirag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** replay 模式确定性：同一数据集与模型连续运行三次，overall 指标完全一致（013 SC-002）。 */
@Tag("eval")
class RagEvaluationDeterminismIT extends AbstractEvaluationIT {

    @TempDir
    Path tempDir;

    @Test
    void replayProducesIdenticalMetricsThreeTimes() throws Exception {
        EvaluationRunner runner = runner();
        List<Map<String, Double>> runs = new ArrayList<>();
        List<String> versions = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            Path reportPath = runner.run(options("run-" + i),
                    new EvaluationHarnessIT.DeterministicEmbeddingModel(8));
            JsonNode report = objectMapper.readTree(Files.readAllBytes(reportPath));
            runs.add(stableOverall(report));
            versions.add(report.path("dataset").path("version").asText());
        }

        assertEquals(runs.get(0), runs.get(1));
        assertEquals(runs.get(0), runs.get(2));
        assertEquals(1, versions.stream().distinct().count());
    }

    private static Map<String, Double> stableOverall(JsonNode report) {
        Map<String, Double> metrics = new java.util.TreeMap<>();
        report.path("overall").fields().forEachRemaining(entry -> {
            if (!entry.getKey().startsWith("latency")) {
                metrics.put(entry.getKey(), entry.getValue().asDouble());
            }
        });
        return metrics;
    }

    private EvaluationOptions options(String suffix) {
        return new EvaluationOptions(
                "sample", RunMode.REPLAY,
                Path.of("eval/datasets/sample"),
                tempDir.resolve("reports-" + suffix),
                tempDir.resolve("baselines"),
                false, null, null, null,
                Path.of("eval/fixtures/sample/embeddings.json"));
    }
}

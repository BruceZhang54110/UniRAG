package com.unirag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 双管线端到端：两条管线、两张向量表，验证文档级去重与片段级保留（013 FR-015）。 */
@Tag("eval")
class RagEvaluationDualPipelineIT extends AbstractEvaluationIT {

    @TempDir
    Path tempDir;

    @Test
    void evaluatesTwoPipelinesAndReportsBoth() throws Exception {
        Path datasetDir = tempDir.resolve("dual");
        Files.createDirectories(datasetDir.resolve("corpus"));
        Files.writeString(datasetDir.resolve("corpus/dual.md"), """
                # 双管线文档

                这是一段用于验证粗粒度与细粒度切片的说明文字。第一段介绍文档摄取、切片和向量化流程。

                这里包含目标片段。它应该被粗粒度与细粒度切片同时覆盖。

                最后一段补充检索、归并与报告输出，确保文档足够长，可以切出多个片段。
                """);
        Files.writeString(datasetDir.resolve("queries.jsonl"), """
                {"id":"d-001","question":"目标片段在哪里？","tags":["fact"],"split":"dev","answerable":true,"relevant":[{"doc":"dual.md","span":"目标片段"}]}
                """);
        Files.writeString(datasetDir.resolve("config.json"), """
                {
                  "name": "dual",
                  "tenant": "eval_dual",
                  "store": {"table": "embeddings_eval_dual_coarse", "dimension": 8},
                  "stores": {
                    "eval-store-coarse": {"table": "embeddings_eval_dual_coarse", "dimension": 8},
                    "eval-store-fine": {"table": "embeddings_eval_dual_fine", "dimension": 8}
                  },
                  "pipelines": [
                    {"key": "coarse", "maxSegmentTokens": 80, "overlapTokens": 10,
                     "modelKey": "eval-embedding", "storeKey": "eval-store-coarse"},
                    {"key": "fine", "maxSegmentTokens": 30, "overlapTokens": 5,
                     "modelKey": "eval-embedding", "storeKey": "eval-store-fine"}
                  ],
                  "retrieval": {"topK": 5, "recallTopK": 10, "minScore": 0.0,
                                "rerank": false, "rewrite": false},
                  "metrics": {"k": [1, 3, 5], "diagnosticTopK": 10}
                }
                """);

        EvaluationOptions options = new EvaluationOptions(
                "dual", RunMode.REPLAY, datasetDir,
                tempDir.resolve("reports"), tempDir.resolve("baselines"),
                false, null, null, null, tempDir.resolve("fixture.json"));

        Path reportPath = runner().run(options, new EvaluationHarnessIT.DeterministicEmbeddingModel(8));
        JsonNode report = objectMapper.readTree(Files.readAllBytes(reportPath));

        assertEquals(2, report.path("config").path("pipelines").size());
        assertEquals(2, report.path("queries").get(0).path("diagnostic").path("searchedTables").asInt());
        assertEquals(1.0, report.path("overall").path("documentRecall@5").asDouble(), 1e-9);
        assertEquals(1.0, report.path("overall").path("chunkRecall@5").asDouble(), 1e-9);

        Set<String> pipelines = new HashSet<>();
        report.path("queries").get(0).path("retrieved")
                .forEach(item -> pipelines.add(item.path("pipeline").asText()));
        assertTrue(pipelines.contains("coarse"), "缺少粗粒度管线结果: " + pipelines);
        assertTrue(pipelines.contains("fine"), "缺少细粒度管线结果: " + pipelines);
    }
}

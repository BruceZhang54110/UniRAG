package com.unirag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 归因端到端场景：阈值过滤产生 THRESHOLD_FILTERED（013 tasks T039）。 */
@Tag("eval")
class RagEvaluationAttributionIT extends AbstractEvaluationIT {

    @TempDir
    Path tempDir;

    @Test
    void reportsThresholdFilteredEndToEnd() throws Exception {
        Path datasetDir = tempDir.resolve("dataset");
        Files.createDirectories(datasetDir.resolve("corpus"));
        Files.writeString(datasetDir.resolve("corpus/a.md"), "# A\n\n无关内容。\n");
        Files.writeString(datasetDir.resolve("corpus/b.md"), "# B\n\n这里包含目标片段。\n");
        Files.writeString(datasetDir.resolve("queries.jsonl"), """
                {"id":"a-001","question":"目标片段在哪里？","tags":["fact"],"split":"dev","answerable":true,"relevant":[{"doc":"b.md","span":"目标片段"}]}
                """);
        Files.writeString(datasetDir.resolve("config.json"), """
                {
                  "name": "attribution",
                  "tenant": "eval_attribution",
                  "store": {"table": "embeddings_eval_attribution", "dimension": 2},
                  "pipelines": [
                    {"key": "fine", "maxSegmentTokens": 64, "overlapTokens": 8,
                     "modelKey": "eval-embedding", "storeKey": "eval-store"}
                  ],
                  "retrieval": {"topK": 1, "recallTopK": 1, "minScore": 0.6,
                                "rerank": false, "rewrite": false},
                  "metrics": {"k": [1], "diagnosticTopK": 2}
                }
                """);

        EvaluationOptions options = new EvaluationOptions(
                "attribution", RunMode.REPLAY, datasetDir,
                tempDir.resolve("reports"), tempDir.resolve("baselines"),
                false, null, null, null, tempDir.resolve("fixture.json"));

        Path reportPath = runner().run(options, new DirectionalEmbeddingModel());
        JsonNode report = objectMapper.readTree(Files.readAllBytes(reportPath));
        JsonNode query = report.path("queries").get(0);

        assertEquals("THRESHOLD_FILTERED", query.path("failure").asText());
        assertEquals(1, report.path("failures").path("THRESHOLD_FILTERED").asInt());
    }

    /** 查询向量与片段向量正交，使生产阈值为 0.6 时被过滤，而诊断遍（minScore=0）仍能召回。 */
    static final class DirectionalEmbeddingModel implements EmbeddingModel {

        private static final String QUERY = "目标片段在哪里？";

        @Override
        public Response<Embedding> embed(String text) {
            return Response.from(Embedding.from(vector(text)));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            return Response.from(textSegments.stream()
                    .map(segment -> Embedding.from(vector(segment.text()))).toList());
        }

        @Override
        public int dimension() {
            return 2;
        }

        @Override
        public String modelName() {
            return "directional-test";
        }

        private static float[] vector(String text) {
            return QUERY.equals(text) ? new float[]{1.0f, 0.0f} : new float[]{0.0f, 1.0f};
        }
    }
}

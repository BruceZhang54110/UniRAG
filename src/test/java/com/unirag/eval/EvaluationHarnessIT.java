package com.unirag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评测链路自测：用确定性嵌入模型跑通 sample 数据集，验证摄取→检索→指标→报告。
 *
 * <p>该测试不属于默认质量门（@Tag("eval")）；用于在没有外部模型凭据时验证 harness。
 */
@Tag("eval")
class EvaluationHarnessIT extends AbstractEvaluationIT {

    @TempDir
    Path tempDir;

    @Test
    void runsSampleDatasetEndToEnd() throws Exception {
        EvaluationOptions options = new EvaluationOptions(
                "sample", RunMode.REPLAY,
                Path.of("eval/datasets/sample"),
                tempDir.resolve("reports"),
                tempDir.resolve("baselines"),
                false, null, null, null,
                tempDir.resolve("fixture.json"));

        Path reportPath = runner().run(options, new DeterministicEmbeddingModel(8));
        assertTrue(Files.exists(reportPath), "报告未生成: " + reportPath);

        JsonNode report = objectMapper.readTree(Files.readAllBytes(reportPath));
        assertEquals("SUCCESS", report.path("run").path("status").asText());
        assertEquals("REPLAY", report.path("run").path("mode").asText());
        assertEquals(5, report.path("dataset").path("queryCount").asInt());
        assertTrue(report.path("overall").has("chunkRecall@5"));
        assertTrue(report.path("overall").has("unanswerableFalsePositiveRate"));
        assertEquals(5, report.path("queries").size());
        assertTrue(Files.exists(reportPath.resolveSibling(reportPath.getFileName().toString()
                .replace(".json", ".md"))), "Markdown 报告未生成");
    }

    /** 测试专用：由文本 SHA-256 派生定长向量，保证可重复，不访问外部服务。 */
    static final class DeterministicEmbeddingModel implements EmbeddingModel {

        private final int dimension;

        DeterministicEmbeddingModel(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public Response<Embedding> embed(String text) {
            return Response.from(Embedding.from(vector(text)));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            List<Embedding> result = new ArrayList<>(textSegments.size());
            textSegments.forEach(segment -> result.add(Embedding.from(vector(segment.text()))));
            return Response.from(result);
        }

        @Override
        public int dimension() {
            return dimension;
        }

        @Override
        public String modelName() {
            return "deterministic-test";
        }

        private float[] vector(String text) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(StandardCharsets.UTF_8));
                float[] vector = new float[dimension];
                for (int i = 0; i < dimension; i++) {
                    vector[i] = ((digest[i] & 0xff) / 255.0f) - 0.5f;
                }
                return vector;
            } catch (Exception e) {
                throw new IllegalStateException("无法生成测试向量", e);
            }
        }
    }
}

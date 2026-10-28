package com.unirag.rag.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SC-001 冻结标注集验收：总体 ≥90%，每类 ≥85%，边界样本单独报告。 */
class QueryRoutingEvaluationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void rulesV1在冻结标注集上达到验收准确率() throws Exception {
        JsonNode root;
        try (InputStream input = getClass().getResourceAsStream("/routing/query-intent-eval.json")) {
            assertThat(input).isNotNull();
            root = OBJECT_MAPPER.readTree(input);
        }

        RuleBasedQueryClassifier classifier = new RuleBasedQueryClassifier();
        Map<QueryIntent, int[]> counts = new EnumMap<>(QueryIntent.class);
        for (QueryIntent intent : QueryIntent.values()) {
            counts.put(intent, new int[]{0, 0});
        }
        int boundaryTotal = 0;
        int boundaryCorrect = 0;
        Map<String, String> failures = new HashMap<>();

        for (JsonNode example : root.path("examples")) {
            QueryIntent expected = QueryIntent.valueOf(example.path("expectedIntent").asText());
            QueryIntent actual = classifier.classify(example.path("question").asText()).intent();
            boolean correct = expected == actual;
            counts.get(expected)[0]++;
            if (correct) {
                counts.get(expected)[1]++;
            } else {
                failures.put(example.path("id").asText(), expected + " -> " + actual);
            }
            if (example.path("boundary").asBoolean(false)) {
                boundaryTotal++;
                if (correct) {
                    boundaryCorrect++;
                }
            }
        }

        int total = counts.values().stream().mapToInt(value -> value[0]).sum();
        int correct = counts.values().stream().mapToInt(value -> value[1]).sum();
        double overall = (double) correct / total;
        assertThat(total).isGreaterThanOrEqualTo(60);
        assertThat(overall)
                .as("overall accuracy must be >= 0.90, failures=%s", failures)
                .isGreaterThanOrEqualTo(0.90);

        counts.forEach((intent, value) -> {
            double accuracy = (double) value[1] / value[0];
            assertThat(value[0]).as("intent count %s", intent).isGreaterThanOrEqualTo(20);
            assertThat(accuracy)
                    .as("intent %s accuracy must be >= 0.85, failures=%s", intent, failures)
                    .isGreaterThanOrEqualTo(0.85);
        });

        assertThat(boundaryTotal).isPositive();
        System.out.printf("classification evaluation: total=%d correct=%d overall=%.2f%%%n",
                total, correct, overall * 100);
        counts.forEach((intent, value) -> System.out.printf(
                "  %s=%d/%d=%.2f%%%n", intent, value[1], value[0], 100.0 * value[1] / value[0]));
        System.out.printf("  boundary=%d/%d=%.2f%%%n", boundaryCorrect, boundaryTotal,
                100.0 * boundaryCorrect / boundaryTotal);
    }
}

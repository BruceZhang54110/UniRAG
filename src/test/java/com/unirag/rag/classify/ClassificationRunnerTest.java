package com.unirag.rag.classify;

import com.unirag.rag.route.RagRoutingProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ClassificationRunner 异常、超时、非法产物与阈值语义验收。 */
class ClassificationRunnerTest {

    private static final RagRoutingProperties PROPERTIES =
            new RagRoutingProperties(0.70, Duration.ofMillis(10));

    @Test
    void 分类器异常被规范为失败且不暴露异常消息() {
        ClassificationRunner runner = new ClassificationRunner(question -> {
            throw new IllegalStateException("api-key=sk-secret");
        }, PROPERTIES);

        ClassificationOutcome outcome = runner.classify("问题");

        assertThat(outcome.status()).isEqualTo(ClassificationStatus.FAILED);
        assertThat(outcome.reasonCode()).isEqualTo("CLASSIFICATION_EXCEPTION");
        assertThat(outcome.intent()).isNull();
        assertThat(outcome.confidence()).isNull();
        assertThat(outcome.evidence()).isEmpty();
    }

    @Test
    void 非法分类产物被规范为INVALID_CLASSIFICATION() {
        ClassificationRunner runner = new ClassificationRunner(question -> {
            throw new IllegalArgumentException("invalid confidence");
        }, PROPERTIES);

        ClassificationOutcome outcome = runner.classify("问题");

        assertThat(outcome.status()).isEqualTo(ClassificationStatus.FAILED);
        assertThat(outcome.reasonCode()).isEqualTo("INVALID_CLASSIFICATION");
    }

    @Test
    void 超过分类预算的结果被规范为超时失败() {
        ClassificationRunner runner = new ClassificationRunner(question -> {
            sleep(30);
            return outcome(ClassificationStatus.SUCCESS, QueryIntent.SIMPLE_FACT, 0.91, "RULE_MATCH");
        }, PROPERTIES);

        ClassificationOutcome outcome = runner.classify("问题");

        assertThat(outcome.status()).isEqualTo(ClassificationStatus.FAILED);
        assertThat(outcome.reasonCode()).isEqualTo("CLASSIFICATION_TIMEOUT");
        assertThat(outcome.durationMs()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void 置信度等于阈值保持成功低于阈值进入低置信度() {
        ClassificationRunner atThreshold = new ClassificationRunner(
                question -> outcome(ClassificationStatus.SUCCESS, QueryIntent.SIMPLE_FACT, 0.70, "RULE_MATCH"),
                PROPERTIES);
        ClassificationRunner belowThreshold = new ClassificationRunner(
                question -> outcome(ClassificationStatus.SUCCESS, QueryIntent.MULTI_HOP_RELATION, 0.69, "RULE_MATCH"),
                PROPERTIES);

        ClassificationOutcome exact = atThreshold.classify("问题");
        ClassificationOutcome below = belowThreshold.classify("问题");

        assertThat(exact.status()).isEqualTo(ClassificationStatus.SUCCESS);
        assertThat(exact.reasonCode()).isEqualTo("RULE_MATCH");
        assertThat(below.status()).isEqualTo(ClassificationStatus.LOW_CONFIDENCE);
        assertThat(below.reasonCode()).isEqualTo("LOW_CONFIDENCE");
        assertThat(below.intent()).isEqualTo(QueryIntent.MULTI_HOP_RELATION);
    }

    private static ClassificationOutcome outcome(ClassificationStatus status, QueryIntent intent,
                                                 double confidence, String reasonCode) {
        return new ClassificationOutcome(status, intent, confidence,
                List.of(new ClassificationEvidence("TEST_RULE", intent, 0.55, "test evidence")),
                reasonCode, "rules-v1", 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

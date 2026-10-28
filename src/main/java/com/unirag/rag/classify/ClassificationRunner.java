package com.unirag.rag.classify;

import com.unirag.rag.route.RagRoutingProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分类保护器（contracts/rag-orchestration.md §2）：计时、校验产物、收敛异常，
 * 并将低置信度/超时/非法结果规范化为路由可用的安全结果。
 */
@Component
public class ClassificationRunner {

    private final QueryClassifier classifier;
    private final RagRoutingProperties properties;

    public ClassificationRunner(QueryClassifier classifier, RagRoutingProperties properties) {
        this.classifier = classifier;
        this.properties = properties;
    }

    public ClassificationOutcome classify(String question) {
        long startedAt = System.nanoTime();
        try {
            ClassificationOutcome outcome = classifier.classify(question);
            long durationMs = elapsedMillis(startedAt);
            if (durationMs > properties.classificationTimeout().toMillis()) {
                return failed("CLASSIFICATION_TIMEOUT", durationMs);
            }
            if (outcome == null) {
                return failed("INVALID_CLASSIFICATION", durationMs);
            }
            ClassificationOutcome normalized = new ClassificationOutcome(outcome.status(), outcome.intent(),
                    outcome.confidence(), outcome.evidence(), outcome.reasonCode(), outcome.ruleVersion(), durationMs);
            if (normalized.status() == ClassificationStatus.FAILED) {
                return normalized;
            }
            if (normalized.confidence() < properties.lowConfidenceThreshold()) {
                return new ClassificationOutcome(ClassificationStatus.LOW_CONFIDENCE, normalized.intent(),
                        normalized.confidence(), normalized.evidence(), "LOW_CONFIDENCE",
                        normalized.ruleVersion(), durationMs);
            }
            return normalized;
        } catch (IllegalArgumentException e) {
            return failed("INVALID_CLASSIFICATION", elapsedMillis(startedAt));
        } catch (RuntimeException e) {
            return failed("CLASSIFICATION_EXCEPTION", elapsedMillis(startedAt));
        }
    }

    private static ClassificationOutcome failed(String reasonCode, long durationMs) {
        return new ClassificationOutcome(ClassificationStatus.FAILED, null, null, List.of(),
                reasonCode, null, durationMs);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}

package com.unirag.rag.classify;

import java.util.List;

/**
 * 一次查询分类结果（data-model.md §2）。FAILED 时 intent/confidence 必须为 null。
 */
public record ClassificationOutcome(ClassificationStatus status,
                                    QueryIntent intent,
                                    Double confidence,
                                    List<ClassificationEvidence> evidence,
                                    String reasonCode,
                                    String ruleVersion,
                                    long durationMs) {

    public ClassificationOutcome {
        if (status == null) {
            throw new IllegalArgumentException("classification status must not be null");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("classification reasonCode must not be blank");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("classification durationMs must be >= 0");
        }
        if (evidence.size() > 8) {
            throw new IllegalArgumentException("classification evidence size must be <= 8");
        }
        if (status == ClassificationStatus.FAILED) {
            if (intent != null || confidence != null) {
                throw new IllegalArgumentException("FAILED classification must not carry intent/confidence");
            }
        } else {
            if (intent == null || confidence == null) {
                throw new IllegalArgumentException("successful classification must carry intent/confidence");
            }
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("classification confidence must be in [0, 1]");
            }
            if (evidence.isEmpty()) {
                throw new IllegalArgumentException("successful classification must carry evidence");
            }
            if (ruleVersion == null || ruleVersion.isBlank()) {
                throw new IllegalArgumentException("successful classification must carry ruleVersion");
            }
        }
    }
}

package com.unirag.rag.classify;

/**
 * rules-v1 的命中证据（data-model.md §2）。description 只解释规则语义，不引用用户问题原文。
 */
public record ClassificationEvidence(String code, QueryIntent intent, double weight, String description) {

    public ClassificationEvidence {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("classification evidence code must not be blank");
        }
        if (intent == null) {
            throw new IllegalArgumentException("classification evidence intent must not be null");
        }
        if (weight <= 0.0 || weight > 1.0) {
            throw new IllegalArgumentException("classification evidence weight must be in (0, 1]");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("classification evidence description must not be blank");
        }
    }
}

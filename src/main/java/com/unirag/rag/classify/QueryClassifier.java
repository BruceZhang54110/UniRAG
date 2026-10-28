package com.unirag.rag.classify;

/** 查询分类器契约（contracts/rag-orchestration.md §2）。 */
public interface QueryClassifier {

    ClassificationOutcome classify(String question);
}

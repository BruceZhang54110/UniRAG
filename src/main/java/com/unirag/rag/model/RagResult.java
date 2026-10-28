package com.unirag.rag.model;

import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.RetrievedChunk;

import java.util.List;

/** 跨范式统一问答结果（data-model.md §3.2）。 */
public record RagResult(String answer,
                        List<RetrievedChunk> citations,
                        boolean degraded,
                        String degradeReason) {

    public static RagResult from(QaAnswer answer) {
        return new RagResult(answer.answer(), answer.citations(), answer.degraded(), answer.degradeReason());
    }

    public QaAnswer toQaAnswer() {
        return new QaAnswer(answer, citations, degraded, degradeReason);
    }

    public RagResult {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("RAG answer must not be blank");
        }
        citations = citations == null ? List.of() : List.copyOf(citations);
        if (degraded && (degradeReason == null || degradeReason.isBlank())) {
            throw new IllegalArgumentException("degraded RAG result must carry degradeReason");
        }
        if (!degraded && degradeReason != null) {
            throw new IllegalArgumentException("non-degraded RAG result must not carry degradeReason");
        }
    }
}

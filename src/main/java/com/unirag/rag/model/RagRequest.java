package com.unirag.rag.model;

import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.model.RetrievalConstraints;

/** 跨范式统一问答请求（data-model.md §3.1）。 */
public record RagRequest(String question,
                         String granularity,
                         int topK,
                         double minScore,
                         String sessionId,
                         Boolean rerank,
                         Boolean rewrite) {

    public static RagRequest from(QaRequest request) {
        return new RagRequest(request.question(), request.granularity(), request.topK(),
                request.minScore(), request.sessionId(), request.rerank(), request.rewrite());
    }

    public RagRequest {
        question = RetrievalConstraints.requireText(question, "question");
        granularity = RetrievalConstraints.normalizeGranularity(granularity);
        topK = RetrievalConstraints.requireTopK(topK);
        minScore = RetrievalConstraints.requireMinScore(minScore);
    }
}

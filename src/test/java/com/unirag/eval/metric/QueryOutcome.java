package com.unirag.eval.metric;

import com.unirag.eval.dataset.QueryCase;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.stage.VectorSearchResult;

import java.util.List;
import java.util.Map;

/** 单条查询的评测结果，保留诊断遍原始数据用于失败归因与报告。 */
public record QueryOutcome(QueryCase query,
                           List<RetrievedChunk> retrieved,
                           VectorSearchResult diagnosticTables,
                           List<RetrievedChunk> diagnosticMerged,
                           Map<String, Double> metrics,
                           long latencyNanos,
                           Attribution attribution) {

    public QueryOutcome {
        retrieved = retrieved == null ? List.of() : List.copyOf(retrieved);
        diagnosticMerged = diagnosticMerged == null ? List.of() : List.copyOf(diagnosticMerged);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        attribution = attribution == null ? Attribution.none() : attribution;
    }
}

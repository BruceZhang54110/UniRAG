package com.unirag.eval.report;

import com.unirag.eval.RunMode;
import com.unirag.eval.dataset.ThresholdRule;
import com.unirag.eval.metric.FailureCategory;

import java.util.List;
import java.util.Map;

/** 评测报告数据模型（013 contracts/report.md §1）。 */
public record EvaluationReport(int reportVersion,
                               RunInfo run,
                               DatasetSnapshot dataset,
                               ConfigSnapshot config,
                               Map<String, Double> overall,
                               Map<String, Map<String, Double>> byTag,
                               List<QueryResult> queries,
                               Map<String, Integer> failures) {

    public enum RunStatus {
        SUCCESS,
        FAILED
    }

    public record RunInfo(String id,
                          RunMode mode,
                          RunStatus status,
                          String startedAt,
                          String finishedAt,
                          String failureReason) {
    }

    public record DatasetSnapshot(String name,
                                  String version,
                                  String corpusHash,
                                  String queriesHash,
                                  int documentCount,
                                  int queryCount,
                                  int answerableCount,
                                  int unanswerableCount) {
    }

    public record ConfigSnapshot(String codeVersion,
                                 EmbeddingSnapshot embedding,
                                 List<PipelineSnapshot> pipelines,
                                 RetrievalSnapshot retrieval,
                                 MetricsSnapshot metrics,
                                 Map<String, ThresholdRule> thresholds) {
    }

    public record EmbeddingSnapshot(String model, int dimension) {
    }

    public record PipelineSnapshot(String key,
                                   int maxSegmentTokens,
                                   int overlapTokens,
                                   String modelKey,
                                   String storeKey) {
    }

    public record RetrievalSnapshot(int topK,
                                    int recallTopK,
                                    double minScore,
                                    boolean rerank,
                                    boolean rewrite) {
    }

    public record MetricsSnapshot(List<Integer> k, int diagnosticTopK) {
    }

    public record QueryResult(String id,
                              String question,
                              List<String> tags,
                              boolean answerable,
                              List<RetrievedItem> retrieved,
                              DiagnosticInfo diagnostic,
                              Map<String, Double> metrics,
                              FailureCategory failure,
                              List<String> evidence) {
    }

    public record RetrievedItem(int rank,
                                String doc,
                                int chunkIndex,
                                String pipeline,
                                double score,
                                Double rerankScore,
                                boolean documentRelevant,
                                boolean chunkRelevant) {
    }

    public record DiagnosticInfo(int searchedTables,
                                 List<String> failedTables,
                                 List<GoldCandidate> goldCandidates) {
    }

    public record GoldCandidate(String doc,
                                String pipeline,
                                int chunkIndex,
                                double score,
                                Integer mergedRank,
                                boolean belowMinScore) {
    }
}

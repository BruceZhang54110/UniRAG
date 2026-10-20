package com.unirag.eval.metric;

import com.unirag.eval.dataset.MetricConfig;
import com.unirag.eval.dataset.QueryCase;
import com.unirag.retrieval.model.RetrievedChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 检索指标计算（013 data-model §3）。 */
public final class RetrievalMetrics {

    public Map<String, Double> perQuery(QueryCase query, List<RetrievedChunk> retrieved,
                                        List<Integer> ks, RelevanceMatcher matcher) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        if (!query.answerable()) {
            metrics.put(MetricKeys.UNANSWERABLE_FALSE_POSITIVE_RATE, retrieved.isEmpty() ? 0.0 : 1.0);
            return metrics;
        }
        Set<String> goldDocs = matcher.goldDocuments(query);
        for (int k : ks) {
            List<RetrievedChunk> top = retrieved.stream().limit(k).toList();
            double documentRecall = goldDocs.isEmpty() ? 0.0
                    : (double) top.stream().map(RetrievedChunk::docName)
                    .filter(goldDocs::contains).distinct().count() / goldDocs.size();
            int matchedLabels = matcher.matchedLabels(top, query.relevant());
            double chunkRecall = query.relevant().isEmpty() ? 0.0
                    : (double) matchedLabels / query.relevant().size();
            int firstRank = matcher.firstRelevantRank(top, query);

            metrics.put("documentRecall@" + k, documentRecall);
            metrics.put("chunkRecall@" + k, chunkRecall);
            metrics.put("hitRate@" + k, firstRank > 0 ? 1.0 : 0.0);
            metrics.put("mrr@" + k, firstRank > 0 ? 1.0 / firstRank : 0.0);
            metrics.put("ndcg@" + k, ndcg(top, query, k, matcher));
        }
        return metrics;
    }

    public Map<String, Double> aggregateOverall(List<QueryOutcome> outcomes, MetricConfig config) {
        Map<String, Double> overall = new LinkedHashMap<>();
        List<QueryOutcome> answerable = outcomes.stream().filter(o -> o.query().answerable()).toList();
        List<QueryOutcome> unanswerable = outcomes.stream().filter(o -> !o.query().answerable()).toList();

        for (int k : config.k()) {
            for (String key : MetricKeys.forK(k)) {
                overall.put(key, mean(answerable.stream().map(o -> o.metrics().getOrDefault(key, 0.0)).toList()));
            }
        }
        overall.put(MetricKeys.UNANSWERABLE_FALSE_POSITIVE_RATE,
                mean(unanswerable.stream()
                        .map(o -> o.metrics().getOrDefault(MetricKeys.UNANSWERABLE_FALSE_POSITIVE_RATE, 0.0))
                        .toList()));

        List<Double> latencies = outcomes.stream()
                .map(o -> o.latencyNanos() / 1_000_000.0).toList();
        overall.put(MetricKeys.LATENCY_P50, percentile(latencies, 50));
        overall.put(MetricKeys.LATENCY_P95, percentile(latencies, 95));
        return overall;
    }

    public Map<String, Map<String, Double>> aggregateByTag(List<QueryOutcome> outcomes, MetricConfig config) {
        Map<String, Map<String, Double>> byTag = new TreeMap<>();
        Map<String, List<QueryOutcome>> grouped = new TreeMap<>();
        for (QueryOutcome outcome : outcomes) {
            for (String tag : outcome.query().tags()) {
                grouped.computeIfAbsent(tag, key -> new ArrayList<>()).add(outcome);
            }
        }
        grouped.forEach((tag, group) -> {
            Map<String, Double> metrics = new LinkedHashMap<>();
            List<QueryOutcome> answerable = group.stream().filter(o -> o.query().answerable()).toList();
            List<QueryOutcome> unanswerable = group.stream().filter(o -> !o.query().answerable()).toList();
            for (int k : config.k()) {
                for (String key : MetricKeys.forK(k)) {
                    List<Double> values = answerable.stream()
                            .map(o -> o.metrics().getOrDefault(key, 0.0)).toList();
                    if (!values.isEmpty()) {
                        metrics.put(key, mean(values));
                    }
                }
            }
            if (!unanswerable.isEmpty()) {
                metrics.put(MetricKeys.UNANSWERABLE_FALSE_POSITIVE_RATE,
                        mean(unanswerable.stream()
                                .map(o -> o.metrics()
                                        .getOrDefault(MetricKeys.UNANSWERABLE_FALSE_POSITIVE_RATE, 0.0))
                                .toList()));
            }
            byTag.put(tag, metrics);
        });
        return byTag;
    }

    private static double ndcg(List<RetrievedChunk> top, QueryCase query, int k, RelevanceMatcher matcher) {
        double dcg = 0.0;
        for (int i = 0; i < top.size(); i++) {
            if (matcher.matchesAny(top.get(i), query)) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int idealCount = Math.min(query.relevant().size(), k);
        double idcg = 0.0;
        for (int i = 1; i <= idealCount; i++) {
            idcg += 1.0 / (Math.log(i + 1) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double mean(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double percentile(List<Double> values, int percentile) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}

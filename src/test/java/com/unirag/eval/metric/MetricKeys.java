package com.unirag.eval.metric;

import com.unirag.eval.dataset.MetricConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 指标键与方向（013 contracts/report.md §2）。 */
public final class MetricKeys {

    public static final String UNANSWERABLE_FALSE_POSITIVE_RATE = "unanswerableFalsePositiveRate";

    public static final String LATENCY_P50 = "latencyP50Ms";

    public static final String LATENCY_P95 = "latencyP95Ms";

    private MetricKeys() {
    }

    public static List<String> forK(int k) {
        return List.of(
                "documentRecall@" + k,
                "chunkRecall@" + k,
                "hitRate@" + k,
                "mrr@" + k,
                "ndcg@" + k);
    }

    public static Set<String> supported(MetricConfig metrics) {
        Set<String> keys = new LinkedHashSet<>();
        metrics.k().forEach(k -> keys.addAll(forK(k)));
        keys.add(UNANSWERABLE_FALSE_POSITIVE_RATE);
        keys.add(LATENCY_P50);
        keys.add(LATENCY_P95);
        return keys;
    }

    /** 越大越好为 true；误答率与耗时为 false。 */
    public static boolean higherIsBetter(String metric) {
        return !UNANSWERABLE_FALSE_POSITIVE_RATE.equals(metric) && !metric.startsWith("latency");
    }

    public static boolean isLatency(String metric) {
        return metric.startsWith("latency");
    }
}

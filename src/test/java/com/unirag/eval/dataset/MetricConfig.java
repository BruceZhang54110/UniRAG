package com.unirag.eval.dataset;

import java.util.List;

/** 指标配置（013 data-model §1.5 MetricConfig）。 */
public record MetricConfig(List<Integer> k, int diagnosticTopK) {

    public MetricConfig {
        k = k == null ? List.of() : List.copyOf(k);
    }
}

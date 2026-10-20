package com.unirag.eval.dataset;

/** 阈值规则：min/max 二选一（013 data-model §1.6）。 */
public record ThresholdRule(Double min, Double max) {
}

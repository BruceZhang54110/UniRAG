package com.unirag.retrieval.web;

import java.util.List;

/**
 * 统一观测查询结果（contracts/retrieval-api.md §4）。
 */
public record ObservationPage(List<ObservationItem> items, long total, int page, int size) {
}

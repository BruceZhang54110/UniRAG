package com.unirag.retrieval.model;

/**
 * 单表检索失败记录（FR-009）：单表故障不影响其余表结果，失败原因摘要随结果返回。
 */
public record FailedTable(String targetKey, String reason) {
}

package com.unirag.retrieval.web;

/**
 * 观测记录条目（contracts/retrieval-api.md §4）。
 *
 * @param tenantCode 发起租户码（严格租户隔离下与本请求租户一致）
 * @param createdAt  ISO-8601 时刻字符串，避免依赖全局日期序列化配置
 */
public record ObservationItem(String traceId,
                              String tenantCode,
                              String stage,
                              String targetKey,
                              String status,
                              Integer durationMs,
                              Integer hitCount,
                              Double topScore,
                              String errorMessage,
                              String createdAt,
                              String detail) {
}

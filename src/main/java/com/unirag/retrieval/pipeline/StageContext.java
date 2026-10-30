package com.unirag.retrieval.pipeline;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 一次检索/问答调用的上下文（contracts/pipeline-extension.md §2，data-model.md §5）。
 *
 * <p>不可变身份信息（链路标识/租户/调用性质）贯穿本次调用的全部阶段与扩展；
 * 阶段之间的共享状态由类型化的输入输出传递，剩余的临时协作数据放 {@link #attributes()}。
 *
 * @param traceId     链路标识，串联本次调用的全部阶段记录（FR-006）
 * @param kind        调用性质（{@code RETRIEVAL} / {@code QA}，data-model.md §1 的 kind 列）
 * @param tenantCode  租户码（资源解析用）
 * @param tenantId    租户主键（落库与租户隔离用）
 * @param sessionId   调用方透传的会话标识，可为空；非空时会话记忆按"租户 + sessionId"读写（015-chat-memory）
 * @param queryDigest 查询/问题原文，落库时截断与脱敏（research.md R3/R6）
 * @param documentId  请求限定的文档标识，可为空
 */
public record StageContext(String traceId,
                           String kind,
                           String tenantCode,
                           Long tenantId,
                           String sessionId,
                           String queryDigest,
                           Long documentId,
                           Map<String, Object> attributes) {

    public StageContext(String traceId, String kind, String tenantCode, Long tenantId,
                        String sessionId, String queryDigest, Long documentId) {
        this(traceId, kind, tenantCode, tenantId, sessionId, queryDigest, documentId, new HashMap<>());
    }

    /** 32 位十六进制链路标识，对齐 {@code retrieval_observation.trace_id} 的列宽。 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    public <T> T get(String key, Class<T> type) {
        return type.cast(attributes.get(key));
    }
}

package com.unirag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.tenant.resource.ResourceType;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 012 US3 验收：QUERY_REWRITE 观测行、指标、降级与跨租户隔离。
 */
class QueryRewriteObservationIT extends AbstractQueryRewriteIT {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void 启用的问答产生独立观测行并共享链路标识() throws Exception {
        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "primary-llm");

        String traceId = ask(TENANT, ORIGINAL, true);
        Map<String, Object> row = rewriteRow(TENANT, traceId);

        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(row.get("target_key")).isEqualTo("rewriteModel");
        assertThat(((Number) row.get("hit_count")).intValue()).isEqualTo(ORIGINAL.length());
        assertThat(((Number) row.get("item_count")).intValue()).isEqualTo(REWRITTEN.length());
        assertThat((String) row.get("detail")).contains("shadow=false", "model=primary-llm", "preview=");
        assertThat(row.get("query_digest")).isEqualTo(ORIGINAL);

        Integer total = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM retrieval_observation WHERE tenant_id = ? AND trace_id = ?
                """, Integer.class, tenantId(TENANT), traceId);
        assertThat(total).isGreaterThan(1);

        assertThat(meterRegistry.get("unirag.query.rewrite.requests")
                .tag("tenant", TENANT).counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.get("unirag.query.rewrite.duration")
                .tag("tenant", TENANT).timer().count()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void 专用模型的观测行标记model_dedicated() throws Exception {
        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "dedicated");

        String traceId = ask(TENANT, ORIGINAL, true);

        assertThat((String) rewriteRow(TENANT, traceId).get("detail")).contains("model=dedicated");
    }

    @Test
    void 改写失败记录降级且脱敏密钥() throws Exception {
        stubQueryTransformerFailure(TENANT, "rewriteModel");

        String traceId = ask(TENANT, ORIGINAL, true);
        Map<String, Object> row = rewriteRow(TENANT, traceId);

        assertThat(row.get("status")).isEqualTo("DEGRADED");
        assertThat((String) row.get("error_message")).contains("api-key=***");
        assertThat((String) row.get("error_message")).doesNotContain("sk-secret");
        assertThat((String) row.get("detail")).doesNotContain("sk-secret");
    }

    @Test
    void 无资源或关闭改写时不写观测行() throws Exception {
        String noneTrace = ask(NONE_TENANT, NONE_QUERY, true);
        assertThat(rewriteCount(NONE_TENANT, noneTrace)).isZero();

        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "primary-llm");
        String closedTrace = ask(TENANT, ORIGINAL, false);
        assertThat(rewriteCount(TENANT, closedTrace)).isZero();
    }

    @Test
    void 跨租户不使用他方改写资源() throws Exception {
        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "primary-llm");

        String noneTrace = ask(NONE_TENANT, NONE_QUERY, true);

        assertThat(rewriteCount(NONE_TENANT, noneTrace)).isZero();
        verify(tenantResourceRouter, never()).getPrimary(eq(NONE_TENANT),
                eq(ResourceType.QUERY_REWRITE_MODEL), eq(QueryTransformer.class));
    }

    private String ask(String tenantCode, String question, boolean rewrite) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", tenantCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"%s","topK":5,"minScore":0.0,"rewrite":%s}
                                """.formatted(question, rewrite)))
                .andExpect(status().isOk())
                .andReturn();
        return OBJECT_MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("traceId").asText();
    }

    private Map<String, Object> rewriteRow(String tenantCode, String traceId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, target_key, hit_count, item_count, error_message, detail, query_digest
                FROM retrieval_observation
                WHERE tenant_id = ? AND trace_id = ? AND stage = 'QUERY_REWRITE'
                """, tenantId(tenantCode), traceId);
    }

    private Integer rewriteCount(String tenantCode, String traceId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM retrieval_observation
                WHERE tenant_id = ? AND trace_id = ? AND stage = 'QUERY_REWRITE'
                """, Integer.class, tenantId(tenantCode), traceId);
    }
}

package com.unirag.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 012 US3 观察模式验收：计算并记录改写，但不改变检索结果。
 */
@TestPropertySource(properties = "unirag.retrieval.query-rewrite.shadow=true")
class QueryRewriteShadowIT extends AbstractQueryRewriteIT {

    @Test
    void 观察模式不改变检索结果但记录改写产物() throws Exception {
        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "primary-llm");

        mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"%s","topK":5,"minScore":0.0,"rewrite":true}
                                """.formatted(ORIGINAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations").isNotEmpty());

        assertThat(lastEmbeddingText.get()).isEqualTo(ORIGINAL);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT status, item_count, detail
                FROM retrieval_observation
                WHERE tenant_id = ? AND stage = 'QUERY_REWRITE'
                ORDER BY id DESC LIMIT 1
                """, tenantId(TENANT));
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) row.get("item_count")).intValue()).isEqualTo(REWRITTEN.length());
        assertThat((String) row.get("detail")).contains("shadow=true", "model=primary-llm");
    }

    @Test
    void 观察模式下改写失败不影响主链路() throws Exception {
        stubQueryTransformerFailure(TENANT, "rewriteModel");

        mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"%s","topK":5,"minScore":0.0,"rewrite":true}
                                """.formatted(ORIGINAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations").isNotEmpty());

        assertThat(lastEmbeddingText.get()).isEqualTo(ORIGINAL);
        String status = jdbcTemplate.queryForObject("""
                SELECT status FROM retrieval_observation
                WHERE tenant_id = ? AND stage = 'QUERY_REWRITE'
                ORDER BY id DESC LIMIT 1
                """, String.class, tenantId(TENANT));
        assertThat(status).isEqualTo("DEGRADED");
    }
}

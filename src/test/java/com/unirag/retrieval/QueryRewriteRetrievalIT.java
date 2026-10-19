package com.unirag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 012 US2 验收：检索接口使用改写后的查询检索，响应结构与过滤语义不变。
 */
class QueryRewriteRetrievalIT extends AbstractQueryRewriteIT {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void 检索使用改写查询且响应query为原问题() throws Exception {
        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "primary-llm");

        mockMvc.perform(post("/api/v1/retrieve")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"%s","topK":5,"minScore":0.0,"rewrite":true}
                                """.formatted(ORIGINAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").value(ORIGINAL))
                .andExpect(jsonPath("$.data.chunks").isNotEmpty());

        assertThat(lastEmbeddingText.get()).isEqualTo(REWRITTEN);
    }

    @Test
    void 显式关闭改写时使用原查询() throws Exception {
        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "primary-llm");

        mockMvc.perform(post("/api/v1/retrieve")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"%s","topK":5,"minScore":0.0,"rewrite":false}
                                """.formatted(ORIGINAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").value(ORIGINAL));

        assertThat(lastEmbeddingText.get()).isEqualTo(ORIGINAL);
        Integer rows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM retrieval_observation
                WHERE tenant_id = ? AND stage = 'QUERY_REWRITE'
                """, Integer.class, tenantId(TENANT));
        assertThat(rows).isZero();
    }

    @Test
    void 无资源租户行为不变() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve")
                        .header("X-Tenant-Code", NONE_TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"%s","topK":5,"minScore":0.0,"rewrite":true}
                                """.formatted(NONE_QUERY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query").value(NONE_QUERY))
                .andExpect(jsonPath("$.data.chunks").isNotEmpty());

        assertThat(noneLastEmbeddingText.get()).isEqualTo(NONE_QUERY);
        Integer rows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM retrieval_observation
                WHERE tenant_id = ? AND stage = 'QUERY_REWRITE'
                """, Integer.class, tenantId(NONE_TENANT));
        assertThat(rows).isZero();
    }

    @Test
    void 粒度与单文档过滤仍生效() throws Exception {
        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "primary-llm");
        Long documentId = jdbcTemplate.queryForObject("""
                SELECT id FROM document WHERE tenant_id = ? AND doc_name = ?
                """, Long.class, tenantId(TENANT), DOC_NAME);

        mockMvc.perform(post("/api/v1/retrieve")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"%s","granularity":"main","topK":5,"minScore":0.0,"rewrite":true}
                                """.formatted(ORIGINAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunks").isNotEmpty());

        MvcResult result = mockMvc.perform(post("/api/v1/retrieve")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"%s","documentId":%d,"topK":5,"minScore":0.0,"rewrite":true}
                                """.formatted(ORIGINAL, documentId)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode chunks = OBJECT_MAPPER.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("chunks");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).path("documentId").asLong()).isEqualTo(documentId);
        assertThat(lastEmbeddingText.get()).isEqualTo(REWRITTEN);
    }
}

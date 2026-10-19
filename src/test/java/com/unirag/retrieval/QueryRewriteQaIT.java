package com.unirag.retrieval;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.scoring.ScoringModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 012 US1 验收：问答使用改写后的检索查询，但提示词、回答与重排仍使用原问题。
 */
class QueryRewriteQaIT extends AbstractQueryRewriteIT {

    @Test
    void 问答使用改写查询检索但提示词保留原问题() throws Exception {
        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "primary-llm");

        mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"%s","topK":5,"minScore":0.0,"rewrite":true}
                                """.formatted(ORIGINAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations", hasSize(1)))
                .andExpect(jsonPath("$.data.citations[0].docName").value(DOC_NAME));

        assertThat(lastEmbeddingText.get()).isEqualTo(REWRITTEN);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        String prompt = ((UserMessage) captor.getValue().messages().get(1)).singleText();
        int contextStart = prompt.indexOf("\n\n资料片段：");
        assertThat(contextStart).isGreaterThan(0);
        assertThat(prompt.substring(0, contextStart)).isEqualTo("问题：" + ORIGINAL);
    }

    @Test
    void 同时启用重排时重排使用原问题() throws Exception {
        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "primary-llm");
        ScoringModel scoring = stubRerankModel(TENANT, segment -> 0.9);

        mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"%s","topK":5,"minScore":0.0,"rerank":true,"rewrite":true}
                                """.formatted(ORIGINAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations").isNotEmpty());

        verify(scoring).scoreAll(anyList(), eq(ORIGINAL));
    }

    @Test
    void 无资源租户不调用改写且行为不变() throws Exception {
        mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", NONE_TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"%s","topK":5,"minScore":0.0,"rewrite":true}
                                """.formatted(NONE_QUERY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations").isNotEmpty());

        assertThat(noneLastEmbeddingText.get()).isEqualTo(NONE_QUERY);
        Integer rows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM retrieval_observation
                WHERE tenant_id = ? AND stage = 'QUERY_REWRITE'
                """, Integer.class, tenantId(NONE_TENANT));
        assertThat(rows).isZero();
    }

    @Test
    void 改写失败时回退原问题并正常回答() throws Exception {
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

    @Test
    void 请求关闭改写时不调用模型() throws Exception {
        stubQueryTransformer(TENANT, "rewriteModel", REWRITTEN, "primary-llm");

        mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"%s","topK":5,"minScore":0.0,"rewrite":false}
                                """.formatted(ORIGINAL)))
                .andExpect(status().isOk());

        assertThat(lastEmbeddingText.get()).isEqualTo(ORIGINAL);
        Integer rows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM retrieval_observation
                WHERE tenant_id = ? AND stage = 'QUERY_REWRITE'
                """, Integer.class, tenantId(TENANT));
        assertThat(rows).isZero();
    }
}

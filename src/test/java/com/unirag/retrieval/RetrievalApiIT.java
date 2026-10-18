package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US1 HTTP 契约验收（contracts/retrieval-api.md §1/§2/§5）：POST /api/v1/retrieve。
 *
 * <p>走 MockMvc 全链路（含既有租户定位拦截器与统一异常处理器），断言成功信封与各错误码。
 * 租户码全局唯一：{@code retrApi}。
 */
class RetrievalApiIT extends AbstractRetrievalIT {

    private static final String TENANT = "retrApi";
    private static final String TENANT_HEADER = "X-Tenant-Code";
    private static final String TABLE = "embeddings_retrapi_main";
    private static final String DOC = "api-doc-a";

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));
    }

    @Test
    void 检索接口返回data信封与带来源元数据的片段() throws Exception {
        retrieve(TENANT, "{\"query\":\"向量数据库\",\"granularity\":\"main\",\"topK\":2}")
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.traceId", matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.data.query").value("向量数据库"))
                .andExpect(jsonPath("$.data.granularity").value("main"))
                .andExpect(jsonPath("$.data.chunks", hasSize(2)))
                .andExpect(jsonPath("$.data.chunks[0].docName").value(DOC))
                .andExpect(jsonPath("$.data.chunks[0].pipelineKey").value("main"))
                .andExpect(jsonPath("$.data.chunks[0].chunkIndex").isNumber())
                .andExpect(jsonPath("$.data.chunks[0].score").isNumber())
                .andExpect(jsonPath("$.data.chunks[0].text").isNotEmpty())
                .andExpect(jsonPath("$.data.searchedTables").value(1))
                .andExpect(jsonPath("$.data.failedTables", hasSize(0)));
    }

    @Test
    void 空query返回VALIDATION_FAILED() throws Exception {
        assertError(retrieve(TENANT, "{\"query\":\"   \"}"), 400, "VALIDATION_FAILED");
    }

    @Test
    void 超长query返回VALIDATION_FAILED() throws Exception {
        String tooLong = "向".repeat(2001);
        assertError(retrieve(TENANT, "{\"query\":\"" + tooLong + "\"}"), 400, "VALIDATION_FAILED");
    }

    @Test
    void topK越界返回VALIDATION_FAILED() throws Exception {
        assertError(retrieve(TENANT, "{\"query\":\"向量数据库\",\"topK\":0}"), 400, "VALIDATION_FAILED");
        assertError(retrieve(TENANT, "{\"query\":\"向量数据库\",\"topK\":51}"), 400, "VALIDATION_FAILED");
    }

    @Test
    void minScore超出取值域返回VALIDATION_FAILED() throws Exception {
        assertError(retrieve(TENANT, "{\"query\":\"向量数据库\",\"minScore\":1.5}"), 400, "VALIDATION_FAILED");
    }

    @Test
    void 不存在的粒度返回GRANULARITY_NOT_FOUND并列出可用粒度() throws Exception {
        assertError(retrieve(TENANT, "{\"query\":\"向量数据库\",\"granularity\":\"ghost\"}"),
                400, "GRANULARITY_NOT_FOUND");
    }

    @Test
    void 缺租户头返回TENANT_CODE_REQUIRED() throws Exception {
        mockMvc.perform(post("/api/v1/retrieve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"向量数据库\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TENANT_CODE_REQUIRED"));
    }

    @Test
    void 未知租户返回TENANT_NOT_FOUND() throws Exception {
        assertError(retrieve("retrApiGhost", "{\"query\":\"向量数据库\"}"), 404, "TENANT_NOT_FOUND");
    }

    private ResultActions retrieve(String tenantCode, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/retrieve")
                .header(TENANT_HEADER, tenantCode)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void assertError(ResultActions actions, int statusCode, String code) throws Exception {
        actions.andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }
}

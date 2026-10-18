package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.model.QaAnswer;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * US2 HTTP 契约验收（contracts/retrieval-api.md §1/§3/§5）：POST /api/v1/ask。
 *
 * <p>走 MockMvc 全链路（含既有租户定位拦截器与统一异常处理器），覆盖成功信封、
 * 参数校验、租户定位失败、模型不可达的 502 映射与"无知识"降级。租户码：{@code qaApi} / {@code qaApiEmpty}。
 */
class QaApiIT extends AbstractRetrievalIT {

    private static final String TENANT = "qaApi";
    private static final String EMPTY_TENANT = "qaApiEmpty";
    private static final String TENANT_HEADER = "X-Tenant-Code";
    private static final String TABLE = "embeddings_qaapi_main";
    private static final String DOC = "qa-api-doc";
    private static final String ANSWER = "资料显示向量库需按租户隔离。[1]";

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
        ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));

        OpenAiChatModel chatModel = stubChatModel(TENANT);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse(ANSWER));

        cleanupTenant(EMPTY_TENANT);
        cleanupObservations(EMPTY_TENANT);
        insertTenant(EMPTY_TENANT);
    }

    @Test
    void 问答接口返回data信封与引用清单() throws Exception {
        ask(TENANT, "{\"question\":\"向量数据库\",\"topK\":2,\"sessionId\":\"s-1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId", matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.data.answer").value(ANSWER))
                .andExpect(jsonPath("$.data.degraded").value(false))
                .andExpect(jsonPath("$.data.degradeReason").doesNotExist())
                .andExpect(jsonPath("$.data.citations", hasSize(1)))
                .andExpect(jsonPath("$.data.citations[0].docName").value(DOC))
                .andExpect(jsonPath("$.data.citations[0].pipelineKey").value("main"))
                .andExpect(jsonPath("$.data.citations[0].chunkIndex").isNumber())
                .andExpect(jsonPath("$.data.citations[0].text").isNotEmpty());
    }

    @Test
    void 检索为空时返回降级文案与空引用() throws Exception {
        ask(EMPTY_TENANT, "{\"question\":\"知识库里有什么？\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.degraded").value(true))
                .andExpect(jsonPath("$.data.degradeReason").value(QaAnswer.NO_CONTEXT))
                .andExpect(jsonPath("$.data.answer").value(QaAnswer.NO_CONTEXT_ANSWER))
                .andExpect(jsonPath("$.data.citations", hasSize(0)));
    }

    @Test
    void 模型不可达返回502_LLM_UNAVAILABLE() throws Exception {
        stubMissingChatModel(TENANT);

        ask(TENANT, "{\"question\":\"向量数据库\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("LLM_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    @Test
    void 空question返回VALIDATION_FAILED() throws Exception {
        ask(TENANT, "{\"question\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 缺租户头返回TENANT_CODE_REQUIRED() throws Exception {
        mockMvc.perform(post("/api/v1/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"向量数据库\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TENANT_CODE_REQUIRED"));
    }

    @Test
    void 不存在的粒度返回GRANULARITY_NOT_FOUND() throws Exception {
        ask(TENANT, "{\"question\":\"向量数据库\",\"granularity\":\"ghost\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("GRANULARITY_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details.available[0]", containsString("main")));
    }

    private ResultActions ask(String tenantCode, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/ask")
                .header(TENANT_HEADER, tenantCode)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}

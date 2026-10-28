package com.unirag.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.AbstractRetrievalIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US2：统一观测入口返回恰好一条可解析的 ROUTE 记录与可空 detail 字段。 */
class QaRouteObservationApiIT extends AbstractRetrievalIT {

    private static final String TENANT = "qaRouteObsApi";
    private static final String TABLE = "embeddings_qarouteobsapi_main";
    private static final String DOC = "qa-route-obs-doc";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private com.unirag.rag.QaService qaService;

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
        var chatModel = stubChatModel(TENANT);
        org.mockito.Mockito.when(chatModel.chat(org.mockito.ArgumentMatchers
                        .any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(chatResponse("观测 API 回答。[1]"));
    }

    @Test
    void 问答可通过统一观测入口查询到一条ROUTE记录并返回detail字段() throws Exception {
        String simpleTrace = ask("什么是租户隔离？");
        String multiTrace = ask("向量数据库与租户隔离之间有什么关系？");
        String complexTrace = ask("请先比较两种摄取方案，再结合知识库内容给出逐步实施建议。");

        assertRouteItem(simpleTrace, "TRADITIONAL_RAG", "SUCCESS");
        assertRouteItem(multiTrace, "GRAPH_RAG", "DEGRADED");
        assertRouteItem(complexTrace, "AGENTIC_RAG", "DEGRADED");
    }

    private String ask(String question) throws Exception {
        String body = mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"%s\",\"topK\":5,\"minScore\":0.0}".formatted(question)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return OBJECT_MAPPER.readTree(body).path("data").path("traceId").asText();
    }

    private void assertRouteItem(String traceId, String targetKey, String status) throws Exception {
        JsonNode data = query("?traceId=" + traceId + "&stage=ROUTE");
        List<JsonNode> items = itemsOf(data);
        assertThat(items).hasSize(1);

        JsonNode item = items.get(0);
        assertThat(item.path("traceId").asText()).isEqualTo(traceId);
        assertThat(item.path("tenantCode").asText()).isEqualTo(TENANT);
        assertThat(item.path("stage").asText()).isEqualTo("ROUTE");
        assertThat(item.path("targetKey").asText()).isEqualTo(targetKey);
        assertThat(item.path("status").asText()).isEqualTo(status);
        assertThat(item.path("hitCount").isNull()).isTrue();
        assertThat(item.path("topScore").isNull()).isTrue();
        assertThat(item.path("durationMs").isInt()).isTrue();
        if ("DEGRADED".equals(status)) {
            assertThat(item.path("errorMessage").asText()).isNotBlank();
        }

        assertThat(item.has("detail")).isTrue();
        JsonNode detail = OBJECT_MAPPER.readTree(item.path("detail").asText());
        assertThat(detail.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(detail.path("classification").path("status").asText()).isNotBlank();
        assertThat(detail.path("route").path("target").asText()).isEqualTo(targetKey);
        assertThat(detail.path("route").path("actual").asText()).isEqualTo("TRADITIONAL_RAG");
        assertThat(detail.path("route").path("candidateParadigms")).isNotEmpty();
        assertThat(detail.path("route").path("capabilityProbes")).isNotEmpty();
    }

    private JsonNode query(String queryString) throws Exception {
        String body = mockMvc.perform(get("/api/v1/observations" + queryString)
                        .header("X-Tenant-Code", TENANT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return OBJECT_MAPPER.readTree(body).path("data");
    }

    private static List<JsonNode> itemsOf(JsonNode data) {
        List<JsonNode> items = new ArrayList<>();
        data.path("items").forEach(items::add);
        return items;
    }
}

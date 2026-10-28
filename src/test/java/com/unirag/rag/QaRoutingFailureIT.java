package com.unirag.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.classify.ClassificationEvidence;
import com.unirag.rag.classify.ClassificationOutcome;
import com.unirag.rag.classify.ClassificationStatus;
import com.unirag.rag.classify.QueryClassifier;
import com.unirag.rag.classify.QueryIntent;
import com.unirag.retrieval.AbstractRetrievalIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** US2：分类异常、非法、超时和低置信度均不阻断问答，并留下可定位 ROUTE 观测。 */
class QaRoutingFailureIT extends AbstractRetrievalIT {

    private static final String TENANT = "qaRoutingFail";
    private static final String TABLE = "embeddings_qaroutingfail_main";
    private static final String DOC = "qa-routing-fail-doc";
    private static final String ANSWER = "失败降级后的回答。[1]";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @MockBean
    private QueryClassifier queryClassifier;

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
        reset(queryClassifier);
    }

    @Test
    void 低置信度分类安全降级并记录复合原因() throws Exception {
        when(queryClassifier.classify(anyString())).thenReturn(outcome(ClassificationStatus.LOW_CONFIDENCE,
                QueryIntent.MULTI_HOP_RELATION, 0.69, "LOW_CONFIDENCE"));

        String traceId = ask("向量数据库与租户隔离之间有什么关系？");

        assertRoute(traceId, "AGENTIC_RAG", "DEGRADED", "LOW_CONFIDENCE");
        assertThat(routeDetail(traceId).path("route").path("reasonCodes"))
                .extracting(JsonNode::asText)
                .containsExactly("LOW_CONFIDENCE", "CAPABILITY_NOT_REGISTERED");
    }

    @Test
    void 非法分类产物安全降级并记录INVALID原因() throws Exception {
        when(queryClassifier.classify(anyString()))
                .thenThrow(new IllegalArgumentException("invalid confidence"));

        String traceId = ask("什么是租户隔离？");

        assertRoute(traceId, "AGENTIC_RAG", "DEGRADED", "INVALID_CLASSIFICATION");
    }

    @Test
    void 分类器抛异常安全降级并记录EXCEPTION原因() throws Exception {
        when(queryClassifier.classify(anyString()))
                .thenThrow(new IllegalStateException("model exploded"));

        String traceId = ask("什么是租户隔离？");

        assertRoute(traceId, "AGENTIC_RAG", "DEGRADED", "CLASSIFICATION_EXCEPTION");
    }

    @Test
    void 分类超过预算安全降级并记录TIMEOUT原因() throws Exception {
        when(queryClassifier.classify(anyString())).thenAnswer(invocation -> {
            Thread.sleep(250);
            return outcome(ClassificationStatus.SUCCESS, QueryIntent.SIMPLE_FACT, 0.91, "RULE_MATCH");
        });

        String traceId = ask("什么是租户隔离？");

        assertRoute(traceId, "AGENTIC_RAG", "DEGRADED", "CLASSIFICATION_TIMEOUT");
    }

    private ClassificationOutcome outcome(ClassificationStatus status, QueryIntent intent,
                                          double confidence, String reasonCode) {
        return new ClassificationOutcome(status, intent, confidence,
                List.of(new ClassificationEvidence("TEST_RULE", intent, 0.55, "test evidence")),
                reasonCode, "rules-v1", 2);
    }

    private String ask(String question) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/ask")
                        .header("X-Tenant-Code", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"%s","topK":5,"minScore":0.0}
                                """.formatted(question)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = OBJECT_MAPPER.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.path("answer").asText()).isEqualTo(ANSWER);
        return data.path("traceId").asText();
    }

    private void assertRoute(String traceId, String targetKey, String status, String reasonCode) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT target_key, status, error_message, detail
                FROM retrieval_observation
                WHERE tenant_id = ? AND trace_id = ? AND stage = 'ROUTE'
                """, tenantId(TENANT), traceId);
        assertThat(row.get("target_key")).isEqualTo(targetKey);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("error_message")).isEqualTo(reasonCode);
        assertThat((String) row.get("detail")).contains("\"actual\":\"TRADITIONAL_RAG\"");
    }

    private JsonNode routeDetail(String traceId) throws Exception {
        String detail = jdbcTemplate.queryForObject("""
                SELECT detail FROM retrieval_observation
                WHERE tenant_id = ? AND trace_id = ? AND stage = 'ROUTE'
                """, String.class, tenantId(TENANT), traceId);
        return OBJECT_MAPPER.readTree(detail);
    }
}

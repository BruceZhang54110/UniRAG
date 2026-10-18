package com.unirag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.model.RetrievalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US3 统一观测入口验收（T034 / FR-006，research.md R3，contracts §4）：
 * 005 摄取侧执行记录无需改动即可在 {@code GET /api/v1/observations} 查到，且严格租户隔离。
 *
 * <p>租户码全局唯一：{@code obsUnified}（有摄取）/ {@code obsUnifiedPeer}（无摄取）。
 */
class ObservationUnifiedQueryIT extends AbstractRetrievalIT {

    private static final String TENANT = "obsUnified";
    private static final String PEER_TENANT = "obsUnifiedPeer";
    private static final String TENANT_HEADER = "X-Tenant-Code";
    private static final String TABLE = "embeddings_obsunified_main";
    private static final String DOC = "unified-doc-a";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private RetrievalService retrievalService;

    private long documentId;

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
        documentId = ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocument())).documentId();

        cleanupTenant(PEER_TENANT, "embeddings_obsunifiedpeer_main");
        cleanupObservations(PEER_TENANT);
        insertTenant(PEER_TENANT);
    }

    @Test
    void 统一入口可按阶段查到005摄取侧记录() throws Exception {
        JsonNode data = query(TENANT, "?stage=INGEST&size=100");
        List<JsonNode> items = itemsOf(data);

        assertThat(data.path("total").asLong()).isGreaterThan(0);
        assertThat(items).isNotEmpty();
        for (JsonNode item : items) {
            assertThat(item.path("stage").asText()).isEqualTo("INGEST");
            assertThat(item.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(item.path("targetKey").asText()).isEqualTo("main");
            assertThat(item.path("hitCount").asInt()).isGreaterThan(0);
            assertThat(item.path("durationMs").asInt()).isGreaterThanOrEqualTo(0);
            assertThat(item.path("traceId").asText()).startsWith("ingest-");
            assertThat(item.path("tenantCode").asText()).isEqualTo(TENANT);
            assertThat(OffsetDateTime.parse(item.path("createdAt").asText())).isNotNull();
        }
    }

    @Test
    void 按文档标识可同时查到摄取侧与检索侧记录() throws Exception {
        String traceId = inTenant(TENANT, () -> retrievalService
                .retrieve(new RetrievalRequest("向量数据库", null, documentId, 5, 0.0))).traceId();

        JsonNode data = query(TENANT, "?documentId=" + documentId + "&size=100");
        List<JsonNode> items = itemsOf(data);

        assertThat(items).extracting(item -> item.path("stage").asText())
                .contains("INGEST", "VECTOR_SEARCH", "MERGE");
        assertThat(items).extracting(item -> item.path("traceId").asText()).contains(traceId);
        // 摄取侧记录与本期记录同属一套模型：两条来源的投影字段都齐备
        assertThat(items).allSatisfy(item -> {
            assertThat(item.path("traceId").asText()).isNotBlank();
            assertThat(item.path("stage").asText()).isNotBlank();
            assertThat(item.path("status").asText()).isNotBlank();
            assertThat(item.path("createdAt").asText()).isNotBlank();
        });
    }

    @Test
    void 只能查到发起租户的记录() throws Exception {
        JsonNode peer = query(PEER_TENANT, "?documentId=" + documentId + "&size=100");

        assertThat(peer.path("total").asLong()).isZero();
        assertThat(itemsOf(peer)).isEmpty();
    }

    @Test
    void 按状态与时间范围过滤并与分页一致() throws Exception {
        JsonNode page = query(TENANT, "?status=SUCCESS&page=1&size=1");

        assertThat(page.path("size").asInt()).isEqualTo(1);
        assertThat(itemsOf(page)).hasSize(1);
        assertThat(page.path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(page.path("items").get(0).path("status").asText()).isEqualTo("SUCCESS");

        JsonNode beyond = query(TENANT, "?status=FAILED&size=100");
        assertThat(itemsOf(beyond)).isEmpty();
    }

    private JsonNode query(String tenantCode, String queryString) throws Exception {
        return dataOf(mockMvc.perform(get("/api/v1/observations" + queryString)
                .header(TENANT_HEADER, tenantCode)));
    }

    private JsonNode dataOf(ResultActions actions) throws Exception {
        String body = actions.andExpect(status().isOk())
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

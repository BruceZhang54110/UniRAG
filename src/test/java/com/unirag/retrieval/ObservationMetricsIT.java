package com.unirag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.model.RetrievalRequest;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US3 聚合指标验收（T037 / FR-007、SC-004，research.md R10）：
 * 检索量/延迟、问答量/延迟/失败率、降级计数与单表故障计数都能从标准监控端点取到，
 * 且带租户维度；标签不使用高基数字段。
 *
 * <p>租户码全局唯一：{@code obsMetrics}。
 */
class ObservationMetricsIT extends AbstractRetrievalIT {

    private static final String TENANT = "obsMetrics";
    private static final String TABLE = "embeddings_obsmetrics_main";
    private static final String DOC = "metrics-doc-a";
    /** 与库中任何片段都不相同的查询：配合 minScore=1 使检索为空，触发问答降级。 */
    private static final String UNMATCHED_QUERY = "zzz-不存在的句子-zzz";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private QaService qaService;

    @Autowired
    private RetrievalService retrievalService;

    private OpenAiChatModel chatModel;

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

        chatModel = stubChatModel(TENANT);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse("回答。[1]"));
    }

    @Test
    void 检索与问答的量与延迟可按租户采集() throws Exception {
        retrieve(new RetrievalRequest("向量数据库", null, null, 5, 0.0));
        ask(new QaRequest("向量数据库", null, 5, 0.0, null));

        // 标签维度：租户粒度可下钻，且不含高基数字段
        assertThat(tagValues(metric("unirag.retrieval.requests"), "tenant")).contains(TENANT);
        assertThat(tagValues(metric("unirag.retrieval.requests"), "status")).contains("SUCCESS");
        assertThat(tagValues(metric("unirag.qa.requests"), "tenant")).contains(TENANT);

        assertThat(countOf(tenantMetric("unirag.retrieval.requests"))).isGreaterThanOrEqualTo(1);
        assertThat(countOf(tenantMetric("unirag.retrieval.duration"))).isGreaterThanOrEqualTo(1);
        assertThat(countOf(tenantMetric("unirag.qa.requests"))).isGreaterThanOrEqualTo(1);
        assertThat(countOf(tenantMetric("unirag.qa.duration"))).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 降级与失败计入问答状态标签() throws Exception {
        assertThat(retrieve(new RetrievalRequest(UNMATCHED_QUERY, null, null, 5, 1.0)).chunks()).isEmpty();
        ask(new QaRequest(UNMATCHED_QUERY, null, 5, 1.0, null));

        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("model down"));
        assertThatThrownBy(() -> ask(new QaRequest("向量数据库", null, 5, 0.0, null)));

        assertThat(tagValues(metric("unirag.qa.requests"), "status")).contains("DEGRADED", "FAILED");
        assertThat(countOf(taggedMetric("unirag.qa.requests", List.of("status:DEGRADED"))))
                .isGreaterThanOrEqualTo(1);
        assertThat(countOf(taggedMetric("unirag.qa.requests", List.of("status:FAILED"))))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void 单表故障计入检索失败指标() throws Exception {
        stubMissingStore(TENANT, "mainStore");

        RetrievalService.RetrievalResult result = retrieve(new RetrievalRequest("向量数据库", null, null, 5, 0.0));

        assertThat(result.failedTables()).hasSize(1);
        assertThat(countOf(tenantMetric("unirag.retrieval.table.failures"))).isGreaterThanOrEqualTo(1);
        assertThat(countOf(taggedMetric("unirag.retrieval.requests", List.of("status:DEGRADED"))))
                .isGreaterThanOrEqualTo(1);
    }

    private RetrievalService.RetrievalResult retrieve(RetrievalRequest request) {
        return inTenant(TENANT, () -> retrievalService.retrieve(request));
    }

    private QaService.QaResult ask(QaRequest request) {
        return inTenant(TENANT, () -> qaService.ask(request));
    }

    /** 不带过滤：可用标签维度完整返回，用于断言租户/状态维度存在。 */
    private JsonNode metric(String name) throws Exception {
        return metricWithTags(name, List.of());
    }

    private JsonNode tenantMetric(String name) throws Exception {
        return metricWithTags(name, List.of("tenant:" + TENANT));
    }

    private JsonNode taggedMetric(String name, List<String> extraTags) throws Exception {
        List<String> tags = new ArrayList<>(List.of("tenant:" + TENANT));
        tags.addAll(extraTags);
        return metricWithTags(name, tags);
    }

    private JsonNode metricWithTags(String name, List<String> tags) throws Exception {
        StringBuilder uri = new StringBuilder("/actuator/metrics/").append(name);
        for (int i = 0; i < tags.size(); i++) {
            uri.append(i == 0 ? '?' : '&').append("tag=").append(tags.get(i));
        }
        ResultActions actions = mockMvc.perform(get(uri.toString()))
                .andExpect(status().isOk());
        String body = actions.andReturn().getResponse().getContentAsString();
        return OBJECT_MAPPER.readTree(body);
    }

    private static double countOf(JsonNode metric) {
        for (JsonNode measurement : metric.path("measurements")) {
            if ("COUNT".equals(measurement.path("statistic").asText())) {
                return measurement.path("value").asDouble();
            }
        }
        return 0;
    }

    private static List<String> tagValues(JsonNode metric, String tag) {
        List<String> values = new ArrayList<>();
        for (JsonNode available : metric.path("availableTags")) {
            if (tag.equals(available.path("tag").asText())) {
                available.path("values").forEach(value -> values.add(value.asText()));
            }
        }
        return values;
    }
}

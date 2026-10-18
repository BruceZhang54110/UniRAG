package com.unirag.retrieval;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * US3 阶段链验收（T033 / spec.md SC-003、FR-006）：一次调用的每个阶段各一条记录，
 * 以同一 {@code traceId} 串联；**断链用例为 0**。
 *
 * <p>断言直接落在 {@code retrieval_observation} 的行上（可观察的持久化结果），
 * 而非仅断言服务返回。租户码全局唯一：{@code obsChain}。
 */
class ObservationChainIT extends AbstractRetrievalIT {

    private static final String TENANT = "obsChain";
    private static final String TABLE = "embeddings_obschain_main";
    private static final String DOC = "chain-doc-a";

    private static final List<String> QA_STAGES = List.of(
            "QUERY_EMBEDDING", "VECTOR_SEARCH", "MERGE", "PROMPT_ASSEMBLY", "CHAT_GENERATION");
    private static final List<String> RETRIEVAL_STAGES = List.of(
            "QUERY_EMBEDDING", "VECTOR_SEARCH", "MERGE");

    @Autowired
    private QaService qaService;

    @Autowired
    private RetrievalService retrievalService;

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
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse("基于资料的回答。[1]"));
    }

    @Test
    void 一次问答产生同链路的完整五个阶段() {
        QaService.QaResult result = inTenant(TENANT,
                () -> qaService.ask(new QaRequest("向量数据库", null, 5, 0.0, "chain-session")));

        List<Map<String, Object>> rows = observationRows();
        assertThat(rows).hasSize(QA_STAGES.size());
        assertThat(rows).extracting(row -> row.get("trace_id")).containsOnly(result.traceId());
        assertThat(rows).extracting(row -> row.get("stage")).containsExactlyElementsOf(QA_STAGES);
        assertThat(rows).extracting(row -> row.get("status"))
                .containsOnly("SUCCESS");
        assertThat(rows).extracting(row -> row.get("kind")).containsOnly("QA");
    }

    @Test
    void 检索与问答各自成链且断链用例为零() {
        QaService.QaResult qa = inTenant(TENANT,
                () -> qaService.ask(new QaRequest("向量数据库", null, 5, 0.0, null)));
        RetrievalService.RetrievalResult retrieval = inTenant(TENANT,
                () -> retrievalService.retrieve(new RetrievalRequest("向量数据库", null, null, 5, 0.0)));

        Map<String, List<String>> stagesByTrace = new LinkedHashMap<>();
        for (Map<String, Object> row : observationRows()) {
            stagesByTrace.computeIfAbsent((String) row.get("trace_id"), key -> new java.util.ArrayList<>())
                    .add((String) row.get("stage"));
        }

        // 两条链路 + 每条链路的阶段集合完备 —— 没有孤儿行，也没有缺阶段的链路
        assertThat(stagesByTrace).hasSize(2);
        assertThat(stagesByTrace.get(qa.traceId())).containsExactlyElementsOf(QA_STAGES);
        assertThat(stagesByTrace.get(retrieval.traceId())).containsExactlyElementsOf(RETRIEVAL_STAGES);
        assertThat(observationRows()).hasSize(QA_STAGES.size() + RETRIEVAL_STAGES.size());
    }

    private List<Map<String, Object>> observationRows() {
        return jdbcTemplate.queryForList(
                "SELECT trace_id, kind, stage, status FROM retrieval_observation WHERE tenant_id = ? ORDER BY id",
                tenantId(TENANT));
    }
}

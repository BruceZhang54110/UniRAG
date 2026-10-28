package com.unirag.rag;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.retrieval.model.QaRequest;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 观测关闭时分类、路由与问答照常，但不持久化 ROUTE 记录。 */
@TestPropertySource(properties = "unirag.observability.enabled=false")
class QaRoutingObservationDisabledIT extends AbstractRetrievalIT {

    private static final String TENANT = "qaRouteObsOff";
    private static final String TABLE = "embeddings_qarouteobsoff_main";
    private static final String DOC = "qa-route-obs-off-doc";

    @Autowired
    private QaService qaService;

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
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse("观测关闭回答。[1]"));
    }

    @Test
    void 观测关闭时问答成功且不写ROUTE记录() {
        QaService.QaResult result = inTenant(TENANT,
                () -> qaService.ask(new QaRequest("什么是租户隔离？", null, 5, 0.0, null)));

        assertThat(result.answer().answer()).isEqualTo("观测关闭回答。[1]");
        assertThat(result.answer().degraded()).isFalse();
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retrieval_observation WHERE tenant_id = ? AND trace_id = ?",
                Integer.class, tenantId(TENANT), result.traceId());
        assertThat(rows).isZero();
    }
}

package com.unirag.retrieval.observation;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.QaService;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.pipeline.StageName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * US4 观测验收（tasks.md T018，FR-008）：带记忆问答后按 traceId 可查 kind=CHAT_MEMORY
 * 记录，detail 仅计数与标志、不含消息原文（宪法 V）。
 *
 * <p>租户码全局唯一：{@code memObs}。
 */
class ChatMemoryObservationIT extends AbstractRetrievalIT {

    private static final String TENANT = "memObs";
    private static final String TABLE = "embeddings_memobs_main";
    private static final String DOC = "mem-obs-doc";
    private static final String SESSION = "s-obs-1";

    @Autowired
    private QaService qaService;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        jdbcTemplate.update("DELETE FROM chat_memory WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                TENANT);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));
        when(stubChatModel(TENANT).chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenAnswer(invocation -> chatResponse("基于资料的回答"));
    }

    @Test
    void 带记忆的问答落CHAT_MEMORY观测且detail不含消息原文() {
        String traceId = inTenant(TENANT, () -> qaService.ask(
                new QaRequest("重排模型有哪些？", null, 5, 0.0, SESSION)).traceId());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT stage, status, item_count, detail FROM retrieval_observation "
                        + "WHERE tenant_id = ? AND trace_id = ? AND stage = ?",
                tenantId(TENANT), traceId, StageName.CHAT_MEMORY.name());
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        // item_count = 记忆窗口消息数（1问1答）
        assertThat(((Number) row.get("item_count")).intValue()).isEqualTo(2);
        String detail = (String) row.get("detail");
        assertThat(detail).contains("\"messageCount\":2").contains("\"degraded\":false");
        assertThat(detail).doesNotContain("重排模型");
    }

    @Test
    void 无sessionId的问答不产生CHAT_MEMORY观测() {
        String traceId = inTenant(TENANT, () -> qaService.ask(
                new QaRequest("重排模型有哪些？", null, 5, 0.0, null)).traceId());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retrieval_observation WHERE tenant_id = ? AND trace_id = ? AND stage = ?",
                Integer.class, tenantId(TENANT), traceId, StageName.CHAT_MEMORY.name());
        assertThat(count).isZero();
    }
}

package com.unirag.retrieval.memory;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.QaService;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.pipeline.StageName;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 故障降级验收（tasks.md T022，FR-007/SC-005）：记忆读写抛异常时，问答仍成功返回
 * （按无记忆完成），观测记 CHAT_MEMORY/DEGRADED。
 *
 * <p>租户码全局唯一：{@code memFlr}。
 */
class ChatMemoryFailureIT extends AbstractRetrievalIT {

    private static final String TENANT = "memFlr";
    private static final String TABLE = "embeddings_memflr_main";
    private static final String DOC = "mem-flr-doc";
    private static final String SESSION = "s-flr-1";

    @Autowired
    private QaService qaService;

    /** 故障记忆：读写全部抛异常，模拟 ChatMemoryStore 不可用。 */
    @MockBean
    private ChatMemoryFactory chatMemoryFactory;

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

        ChatMemory fault = org.mockito.Mockito.mock(ChatMemory.class);
        when(fault.messages()).thenThrow(new IllegalStateException("store down"));
        doThrow(new IllegalStateException("store down")).when(fault).add(any(ChatMessage.class));
        when(chatMemoryFactory.get(anyLong(), anyString())).thenReturn(Optional.of(fault));
    }

    @Test
    void 记忆故障时问答成功且观测记降级() {
        QaService.QaResult result = inTenant(TENANT, () -> qaService.ask(
                new QaRequest("重排模型有哪些？", null, 5, 0.0, SESSION)));

        QaAnswer answer = result.answer();
        assertThat(answer.answer()).isEqualTo("基于资料的回答");
        assertThat(answer.degraded()).isFalse();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, error_message FROM retrieval_observation "
                        + "WHERE tenant_id = ? AND trace_id = ? AND stage = ?",
                tenantId(TENANT), result.traceId(), StageName.CHAT_MEMORY.name());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("DEGRADED");
        assertThat((String) rows.get(0).get("error_message")).contains("IllegalStateException");
    }
}

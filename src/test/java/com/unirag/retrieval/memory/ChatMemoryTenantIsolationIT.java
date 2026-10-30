package com.unirag.retrieval.memory;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.QaService;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.retrieval.model.QaRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 租户隔离验收（tasks.md T023，FR-002，宪法 IV）：两租户共用同一 sessionId，
 * 记忆严格互不可见；清空只影响本租户会话。
 *
 * <p>租户码全局唯一：{@code memIsoA} / {@memIsoB}。
 */
class ChatMemoryTenantIsolationIT extends AbstractRetrievalIT {

    private static final String TENANT_A = "memIsoA";
    private static final String TENANT_B = "memIsoB";
    private static final String TABLE_A = "embeddings_memisoa_main";
    private static final String TABLE_B = "embeddings_memisob_main";
    private static final String SHARED_SESSION = "s-shared-1";

    @Autowired
    private QaService qaService;

    private final ConcurrentMap<String, AtomicReference<ChatRequest>> lastRequestByTenant =
            new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        for (String tenant : new String[]{TENANT_A, TENANT_B}) {
            String table = tenant.equals(TENANT_A) ? TABLE_A : TABLE_B;
            cleanupTenant(tenant, table);
            cleanupObservations(tenant);
            jdbcTemplate.update(
                    "DELETE FROM chat_memory WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                    tenant);
            long tenantId = insertTenant(tenant);
            insertStoreConfig(tenantId, "mainStore", table, 1024);
            insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        }
        registrar.loadAndRegister();
        stubRouter(TENANT_A, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT_A, "mainStore")));
        stubRouter(TENANT_B, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT_B, "mainStore")));
        ingestionService.ingest(new IngestRequest(
                TENANT_A, "iso-doc-a", null, IngestionTestDocuments.longChineseDocument()));
        ingestionService.ingest(new IngestRequest(
                TENANT_B, "iso-doc-b", null, IngestionTestDocuments.longChineseDocument()));
        recordChatModel(TENANT_A);
        recordChatModel(TENANT_B);
    }

    @Test
    void 同sessionId跨租户记忆互不可见且清空互不影响() {
        inTenant(TENANT_A, () -> qaService.ask(
                new QaRequest("租户A的保密问题？", null, 5, 0.0, SHARED_SESSION)));

        // 租户 B 用同一 sessionId 追问：LLM 输入不得包含租户 A 的任何历史
        inTenant(TENANT_B, () -> qaService.ask(
                new QaRequest("它指的是什么？", null, 5, 0.0, SHARED_SESSION)));
        List<ChatMessage> bMessages = new ArrayList<>(
                lastRequestByTenant.get(TENANT_B).get().messages());
        assertThat(bMessages.toString()).doesNotContain("租户A的保密问题");
        assertThat(bMessages).hasSize(2); // 仅 System + 本轮问题

        // 存储层：两个租户各自的 memoryId 行独立存在
        assertThat(countMemoryRows(TENANT_A)).isOne();
        assertThat(countMemoryRows(TENANT_B)).isOne();

        // 清空租户 A 的会话：租户 B 的记忆不受影响
        inTenant(TENANT_A, () -> {
            chatMemoryFactory().clear(tenantId(TENANT_A), SHARED_SESSION);
            return null;
        });
        assertThat(countMemoryRows(TENANT_A)).isZero();
        assertThat(countMemoryRows(TENANT_B)).isOne();
    }

    private com.unirag.retrieval.memory.ChatMemoryFactory chatMemoryFactory() {
        return applicationContext.getBean(com.unirag.retrieval.memory.ChatMemoryFactory.class);
    }

    private Long countMemoryRows(String tenantCode) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_memory WHERE memory_id = ?", Long.class,
                tenantId(tenantCode) + ":" + SHARED_SESSION);
    }

    private void recordChatModel(String tenantCode) {
        OpenAiChatModel model = stubChatModel(tenantCode);
        AtomicReference<ChatRequest> ref = lastRequestByTenant.computeIfAbsent(
                tenantCode, ignored -> new AtomicReference<>());
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ref.set(invocation.getArgument(0));
            return chatResponse("基于资料的回答");
        });
    }
}

package com.unirag.retrieval.stage;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.QaService;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.QaRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * US1 验收（specs/015-chat-memory/tasks.md T007，FR-001）：同一 sessionId 的两轮问答中，
 * 第二轮发给 LLM 的消息 MUST 按序包含 SystemMessage → 第一轮 UserMessage → 第一轮 AiMessage
 * → 第二轮 UserMessage（data-model.md §4）；本轮问答落库 chat_memory。
 *
 * <p>租户码全局唯一：{@code memUS1}。
 */
class ChatGenerationStageMemoryIT extends AbstractRetrievalIT {

    private static final String TENANT = "memUS1";
    private static final String TABLE = "embeddings_memus1_main";
    private static final String DOC = "mem-us1-doc";
    private static final String SESSION = "s-mem-1";

    @Autowired
    private QaService qaService;

    private final AtomicReference<ChatRequest> lastRequest = new AtomicReference<>();

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
        OpenAiChatModel chatModel = stubChatModel(TENANT);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            lastRequest.set(invocation.getArgument(0));
            return chatResponse("基于资料的回答");
        });
    }

    @Test
    void 同会话第二轮问题利用记忆窗口且顺序正确() {
        inTenant(TENANT, () -> qaService.ask(
                new QaRequest("UniRAG 支持哪些重排模型？", null, 5, 0.0, SESSION)));
        inTenant(TENANT, () -> qaService.ask(
                new QaRequest("它默认超时是多少？", null, 5, 0.0, SESSION)));

        ChatRequest second = lastRequest.get();
        List<ChatMessage> messages = new ArrayList<>(second.messages());
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        // 记忆窗口内是问答原文：第一轮问题不带资料片段
        assertThat(((UserMessage) messages.get(1)).singleText()).isEqualTo("UniRAG 支持哪些重排模型？");
        assertThat(messages.get(2)).isInstanceOf(AiMessage.class);
        // 本轮 LLM 输入仍是"问题 + 资料片段"的完整组装提示词
        assertThat(((UserMessage) messages.get(3)).singleText())
                .contains("它默认超时是多少？")
                .contains("资料片段");

        // 本轮问答已持久化（FR-003）：一问一答成对、不留孤儿消息；只存原文不含资料片段
        String stored = jdbcTemplate.queryForObject(
                "SELECT messages FROM chat_memory WHERE memory_id = ?",
                String.class, tenantId(TENANT) + ":" + SESSION);
        assertThat(stored).contains("UniRAG 支持哪些重排模型？")
                .contains("它默认超时是多少？")
                .doesNotContain("资料片段");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT jsonb_array_length(messages::jsonb) FROM chat_memory WHERE memory_id = ?",
                Integer.class, tenantId(TENANT) + ":" + SESSION))
                .isEqualTo(4);
    }
}

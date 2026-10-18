package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageStatus;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.upload.support.ApiException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * US2 降级语义验收（spec.md FR-005、SC-005，contracts/retrieval-api.md §3）：
 * 检索为空 → 降级且**不调用模型**；模型不可达/返回空 → 可读失败且原因不含密钥（FR-008）。
 *
 * <p>租户码全局唯一：{@code qaDeg}（有内容，用于模型故障路径）/ {@code qaEmpty}（无启用管线）。
 */
class QaDegradationIT extends AbstractRetrievalIT {

    private static final String TENANT = "qaDeg";
    private static final String EMPTY_TENANT = "qaEmpty";
    private static final String TABLE = "embeddings_qadeg_main";
    private static final String DOC = "qa-degradation-doc";
    private static final String QUESTION = "向量数据库的介绍";
    private static final String FAKE_SECRET = "sk-degradation-secret-123";

    @Autowired
    private QaService qaService;

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

        cleanupTenant(EMPTY_TENANT);
        cleanupObservations(EMPTY_TENANT);
        insertTenant(EMPTY_TENANT);
    }

    @Test
    void 检索为空时降级且不调用模型() {
        OpenAiChatModel emptyTenantModel = stubChatModel(EMPTY_TENANT);

        QaService.QaResult result = inTenant(EMPTY_TENANT,
                () -> qaService.ask(new QaRequest("知识库里有什么？", null, 5, 0.0, null)));

        QaAnswer answer = result.answer();
        assertThat(answer.degraded()).isTrue();
        assertThat(answer.degradeReason()).isEqualTo(QaAnswer.NO_CONTEXT);
        assertThat(answer.answer()).isEqualTo(QaAnswer.NO_CONTEXT_ANSWER);
        assertThat(answer.citations()).isEmpty();
        // 无上下文时 MUST NOT 让模型自由作答：连模型资源都不该被解析（FR-005）
        verify(tenantResourceRouter, never()).getPrimary(eq(EMPTY_TENANT), eq(ResourceType.LLM),
                eq(OpenAiChatModel.class));
        verifyNoInteractions(emptyTenantModel);
        // 降级仍要留下完整阶段链，且问答生成阶段记为 DEGRADED（data-model.md §6）
        assertThat(stagesOf(EMPTY_TENANT)).containsExactly(
                StageName.QUERY_EMBEDDING.name(), StageName.VECTOR_SEARCH.name(), StageName.MERGE.name(),
                StageName.PROMPT_ASSEMBLY.name(), StageName.CHAT_GENERATION.name());
        assertThat(statusOf(EMPTY_TENANT, StageName.CHAT_GENERATION)).isEqualTo(StageStatus.DEGRADED.name());
    }

    @Test
    void 租户聊天模型未配置时返回LLM_UNAVAILABLE() {
        stubMissingChatModel(TENANT);

        assertThatThrownBy(this::ask)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("LLM_UNAVAILABLE"));
    }

    @Test
    void 租户嵌入模型未配置时返回EMBEDDING_MODEL_UNAVAILABLE() {
        stubMissingEmbeddingModel(TENANT, "default");

        assertThatThrownBy(this::ask)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("EMBEDDING_MODEL_UNAVAILABLE"));
    }

    @Test
    void 模型调用失败时返回LLM_UNAVAILABLE且原因不含密钥() {
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(
                new IllegalStateException("connect timed out (api-key=" + FAKE_SECRET + ")"));

        assertThatThrownBy(this::ask)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("LLM_UNAVAILABLE");
                    assertThat(e.getMessage()).contains("connect timed out").doesNotContain(FAKE_SECRET);
                });

        List<String> recordedMessages = jdbcTemplate.queryForList(
                "SELECT error_message FROM retrieval_observation WHERE tenant_id = ? AND error_message IS NOT NULL",
                String.class, tenantId(TENANT));
        assertThat(recordedMessages).isNotEmpty();
        assertThat(recordedMessages).allSatisfy(message -> assertThat(message).doesNotContain(FAKE_SECRET));
    }

    @Test
    void 模型返回空内容时返回LLM_EMPTY_RESPONSE() {
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse("   "));

        assertThatThrownBy(this::ask)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("LLM_EMPTY_RESPONSE"));
    }

    @Test
    void 正常问答产生完整阶段链且全部成功() {
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse("正常回答"));

        ask();

        assertThat(stagesOf(TENANT)).containsExactly(
                StageName.QUERY_EMBEDDING.name(), StageName.VECTOR_SEARCH.name(), StageName.MERGE.name(),
                StageName.PROMPT_ASSEMBLY.name(), StageName.CHAT_GENERATION.name());
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM retrieval_observation WHERE tenant_id = ?", String.class, tenantId(TENANT)))
                .containsOnly(StageStatus.SUCCESS.name());
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT trace_id FROM retrieval_observation WHERE tenant_id = ?",
                String.class, tenantId(TENANT))).hasSize(1);
    }

    private QaService.QaResult ask() {
        return inTenant(TENANT, () -> qaService.ask(new QaRequest(QUESTION, null, 5, 0.0, "session-degradation")));
    }

    private List<String> stagesOf(String tenantCode) {
        return jdbcTemplate.queryForList(
                "SELECT stage FROM retrieval_observation WHERE tenant_id = ? ORDER BY id",
                String.class, tenantId(tenantCode));
    }

    private String statusOf(String tenantCode, StageName stage) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM retrieval_observation WHERE tenant_id = ? AND stage = ? ORDER BY id LIMIT 1",
                String.class, tenantId(tenantCode), stage.name());
    }
}

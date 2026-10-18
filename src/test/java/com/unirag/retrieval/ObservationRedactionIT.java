package com.unirag.retrieval;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.model.QaRequest;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * US3 敏感信息零泄漏验收（T035 / FR-008、SC-006，宪法原则 V）：
 * 观测记录、统一查询入口响应与进程日志中都不得出现 api-key / 密码 / 配置原文。
 *
 * <p>用**必含密钥的失败原因**驱动最可能的泄漏路径（模型调用失败），并同时检查三条出口。
 * 租户码全局唯一：{@code obsRedact}。
 */
class ObservationRedactionIT extends AbstractRetrievalIT {

    private static final String TENANT = "obsRedact";
    private static final String TABLE = "embeddings_obsredact_main";
    private static final String DOC = "redact-doc-a";
    private static final String API_KEY_SECRET = "sk-redaction-probe-1234567890";
    private static final String PASSWORD_SECRET = "pg-redaction-probe-pw";
    private static final String PROBE_QUESTION = "文档里写的密码 password=" + PASSWORD_SECRET + " 是真的吗？";

    private static final String OBSERVATIONS_PATH = "/api/v1/observations";

    @Autowired
    private QaService qaService;

    private OpenAiChatModel chatModel;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        // 捕获必须在摄取之前挂载：摄取日志同时用来证明捕获管道本身生效
        logAppender = attachRootLogAppender();
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
    }

    @AfterEach
    void tearDown() {
        detach(logAppender);
    }

    @Test
    void 模型失败原因中的密钥不得出现在观测记录日志与响应中() {
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException(
                "401 unauthorized: api-key=" + API_KEY_SECRET + ", password=" + PASSWORD_SECRET));

        assertThatThrownBy(() -> inTenant(TENANT,
                () -> qaService.ask(new QaRequest("向量数据库", null, 5, 0.0, null))))
                .hasMessageNotContaining(API_KEY_SECRET)
                .hasMessageNotContaining(PASSWORD_SECRET);

        assertNoSecrets(observationTexts());
        assertLogsCapturedAndClean();
    }

    @Test
    void 问题文本中的密钥在观测记录里被脱敏() {
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse("回答。[1]"));

        inTenant(TENANT, () -> qaService.ask(new QaRequest(PROBE_QUESTION, null, 5, 0.0, null)));

        List<String> digests = jdbcTemplate.queryForList(
                "SELECT query_digest FROM retrieval_observation WHERE tenant_id = ?", String.class, tenantId(TENANT));
        assertThat(digests).isNotEmpty();
        assertThat(digests).allSatisfy(digest -> assertThat(digest).doesNotContain(PASSWORD_SECRET));
        assertLogsCapturedAndClean();
    }

    @Test
    void 统一查询入口返回的记录不含配置原文与密钥() throws Exception {
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(
                new IllegalStateException("connect failed, api-key=" + API_KEY_SECRET));

        assertThatThrownBy(() -> inTenant(TENANT,
                () -> qaService.ask(new QaRequest(PROBE_QUESTION, null, 5, 0.0, null))));

        String body = mockMvc.perform(get(OBSERVATIONS_PATH + "?size=100")
                        .header("X-Tenant-Code", TENANT))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isNotBlank();
        assertThat(body).doesNotContain(API_KEY_SECRET).doesNotContain(PASSWORD_SECRET);
        assertNoSecrets(observationTexts());
    }

    /** 先证明日志确实被捕获（同窗口内的摄取日志必然存在），再断言捕获到的内容不含密钥。 */
    private void assertLogsCapturedAndClean() {
        List<String> logs = capturedLogMessages();
        assertThat(logs).anySatisfy(line -> assertThat(line).contains("Pipeline ingestion succeeded"));
        assertNoSecrets(logs);
    }

    private void assertNoSecrets(List<String> texts) {
        assertThat(texts).isNotEmpty();
        assertThat(texts).allSatisfy(text -> {
            assertThat(text).doesNotContain(API_KEY_SECRET);
            assertThat(text).doesNotContain(PASSWORD_SECRET);
            assertThat(text).doesNotContain(databasePassword());
        });
    }

    /** 观测记录的全部可读文本出口（错误原因与查询摘要）。 */
    private List<String> observationTexts() {
        return jdbcTemplate.queryForList(
                "SELECT COALESCE(error_message, '') || ' ' || COALESCE(query_digest, '') FROM retrieval_observation "
                        + "WHERE tenant_id = ?",
                String.class, tenantId(TENANT));
    }

    private String databasePassword() {
        return environment.getProperty("spring.datasource.password");
    }

    private List<String> capturedLogMessages() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static ListAppender<ILoggingEvent> attachRootLogAppender() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
    }
}

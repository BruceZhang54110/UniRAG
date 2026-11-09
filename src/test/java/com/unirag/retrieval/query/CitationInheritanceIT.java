package com.unirag.retrieval.query;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionService;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.infrastructure.persistence.mapper.ChatMessageMapper;
import com.unirag.rag.QaService;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.model.RetrievedChunk;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 023 US1/US2 验收（spec.md US1/US2，SC-001/002/003）：指代型追问的引用继承端到端。
 * 正路径断言答案引用全部来自被继承文档；放弃路径以 DEBUG 观测日志的 reason 断言
 * （日志为 FR-006 观测形态之一）。租户码全局唯一：{@code citInh} / {@code citInhPeer}。
 */
class CitationInheritanceIT extends AbstractRetrievalIT {

    private static final String TENANT = "citInh";
    private static final String PEER = "citInhPeer";
    private static final String TABLE = "embeddings_citinh_main";
    private static final String PEER_TABLE = "embeddings_citinhpeer_main";
    private static final String DOC_A = "cit-doc-a";
    private static final String DOC_B = "cit-doc-b";
    private static final String ANSWER = "根据提供的资料，相关内容如下。[1]";

    @Autowired QaService qaService;
    @Autowired IngestionService ingestionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatMessageMapper chatMessageMapper;

    private OpenAiChatModel chatModel;
    private ListAppender<ILoggingEvent> inheritLog;

    private long tenantId;
    private long documentAId;
    private long documentBId;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        cleanupTenant(PEER, PEER_TABLE);
        cleanupObservations(TENANT);
        cleanupObservations(PEER);
        jdbc.update("DELETE FROM chat_message WHERE session_id LIKE 'cit-inh-%'");
        tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        long peerTenantId = insertTenant(PEER);
        insertStoreConfig(peerTenantId, "mainStore", PEER_TABLE, 1024);
        insertPipeline(peerTenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
        stubRouter(PEER, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(PEER, "mainStore")));
        inTenant(TENANT, () -> {
            ingestionService.ingest(new IngestRequest(TENANT, DOC_A, null, IngestionTestDocuments.longChineseDocument()));
            ingestionService.ingest(new IngestRequest(TENANT, DOC_B, null,
                    "completely unrelated english corpus about sailing knots and harbor tides."));
            return null;
        });
        inTenant(PEER, () -> ingestionService.ingest(new IngestRequest(PEER, DOC_B, null,
                IngestionTestDocuments.shortChineseDocument())));
        documentAId = documentId(TENANT, DOC_A);
        documentBId = documentId(TENANT, DOC_B);

        chatModel = stubChatModel(TENANT);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse(ANSWER));
        OpenAiChatModel peerChatModel = stubChatModel(PEER);
        when(peerChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse(ANSWER));

        Logger logger = (Logger) LoggerFactory.getLogger("com.unirag.rag.route.TraditionalRagExecutor");
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        inheritLog = new ListAppender<>();
        inheritLog.start();
        logger.addAppender(inheritLog);
    }

    @AfterEach
    void tearDownAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.unirag.rag.route.TraditionalRagExecutor");
        logger.detachAppender(inheritLog);
        logger.setLevel(null);
    }

    @Test
    void anaphoraFollowUpInheritsPreviousDocument() {
        String session = "cit-inh-s1";
        QaService.QaResult first = ask(session, "多租户隔离方案的核心内容是什么");
        assertThat(first.answer().citations()).isNotEmpty();
        assertThat(first.answer().citations())
                .allSatisfy(c -> assertThat(c.documentId()).isEqualTo(documentAId));
        // T007：最终引用已落 chat_message（保留重复计票）
        String cited = jdbc.queryForObject(
                "SELECT cited_document_ids FROM chat_message WHERE tenant_id = ? AND session_id = ? "
                        + "AND role = 'AI' AND status = 'SUCCESS' ORDER BY round_seq DESC LIMIT 1",
                String.class, tenantId, session);
        assertThat(cited).isNotBlank();

        QaService.QaResult followUp = ask(session, "这篇文档还有别的要求吗");
        assertThat(followUp.answer().citations()).isNotEmpty();
        assertThat(followUp.answer().citations())
                .allSatisfy(c -> assertThat(c.documentId()).as("继承后候选必须全部来自文档 A").isEqualTo(documentAId));
        assertLogContains("INHERITED:" + documentAId);
        // US2：每轮问答恰一行 CITATION_INHERIT 观测，target_key 形态正确
        assertObservationRow(followUp.traceId(), "INHERITED:" + documentAId);
    }

    @Test
    void newTopicWithoutAnaphoraDoesNotInherit() {
        String session = "cit-inh-s2";
        ask(session, "多租户隔离方案的核心内容是什么");

        QaService.QaResult result = ask(session, "completely unrelated english question about tides");
        assertThat(result.answer().citations()).isNotEmpty();
        assertLogContains("target=NO_ANAPHORA");
        assertObservationRow(result.traceId(), "NO_ANAPHORA");
    }

    @Test
    void ambiguousCitationsFailOpen() {
        String session = "cit-inh-s3";
        insertAiRound(session, "SUCCESS", documentAId + "," + documentBId);

        QaService.QaResult result = ask(session, "这篇文档的重点是什么");
        assertThat(result.answer().answer()).isEqualTo(ANSWER);
        assertLogContains("target=AMBIGUOUS_CITATIONS");
    }

    @Test
    void previousFailedRoundNotCitable() {
        String session = "cit-inh-s4";
        insertAiRound(session, "FAILED", null);

        QaService.QaResult result = ask(session, "这篇文档的重点是什么");
        assertThat(result.answer().answer()).isEqualTo(ANSWER);
        assertLogContains("target=PREVIOUS_NOT_CITABLE");
    }

    @Test
    void crossTenantSameSessionSeesNothing() {
        String session = "cit-inh-s5";
        ask(session, "多租户隔离方案的核心内容是什么");
        inheritLog.list.clear();

        // 对端租户同名会话：解析不到本租户历史，零泄漏（宪法 IV / SC-003）
        QaService.QaResult peerResult = inTenant(PEER,
                () -> qaService.ask(new QaRequest("这篇文档的重点是什么", null, 5, 0.0, session)));
        assertThat(peerResult.answer().answer()).isEqualTo(ANSWER);
        assertThat(peerResult.answer().citations())
                .allSatisfy(c -> assertThat(c.documentId())
                        .as("对端租户回答不得引用本租户文档").isNotEqualTo(documentAId));
        assertLogContains("target=NO_PREVIOUS_ROUND");
    }

    @Test
    void deletedCitedDocumentFailsOpen() {
        String session = "cit-inh-s6";
        insertAiRound(session, "SUCCESS", String.valueOf(documentAId));
        jdbc.update("UPDATE document SET deleted = TRUE WHERE id = ?", documentAId);
        try {
            QaService.QaResult result = ask(session, "这篇文档的重点是什么");
            assertThat(result.answer().answer()).isEqualTo(ANSWER);
            assertLogContains("target=DOCUMENT_DELETED");
        } finally {
            jdbc.update("UPDATE document SET deleted = FALSE WHERE id = ?", documentAId);
        }
    }

    @Test
    void noSessionSkips() {
        QaService.QaResult result = ask(null, "这篇文档的重点是什么");
        assertThat(result.answer().answer()).isEqualTo(ANSWER);
        assertLogContains("target=NO_SESSION");
    }

    private QaService.QaResult ask(String sessionId, String question) {
        return inTenant(TENANT, () -> qaService.ask(new QaRequest(question, null, 5, 0.0, sessionId)));
    }

    private void insertAiRound(String sessionId, String status, String citedDocumentIds) {
        long roundSeq = chatMessageMapper.nextRoundSeq(tenantId, sessionId);
        jdbc.update("INSERT INTO chat_message (tenant_id, session_id, round_seq, role, content, status, "
                        + "degrade_reason, trace_id, cited_document_ids, created_at) "
                        + "VALUES (?, ?, ?, 'AI', '', ?, NULL, NULL, ?, now())",
                tenantId, sessionId, roundSeq, status, citedDocumentIds);
    }

    private long documentId(String tenant, String docName) {
        return jdbc.queryForObject(
                "SELECT id FROM document WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?",
                Long.class, tenant, docName);
    }

    private void assertLogContains(String fragment) {
        assertThat(inheritLog.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .as("应出现含 [%s] 的 CITATION_INHERIT 观测日志", fragment)
                        .contains("CITATION_INHERIT").contains(fragment));
    }

    private void assertObservationRow(String traceId, String expectedTargetKey) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, target_key FROM retrieval_observation "
                        + "WHERE trace_id = ? AND stage = 'CITATION_INHERIT'", traceId);
        assertThat(row.get("target_key")).isEqualTo(expectedTargetKey);
        assertThat(row.get("status")).isIn("SUCCESS", "DEGRADED");
    }
}

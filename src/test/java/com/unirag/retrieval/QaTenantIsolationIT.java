package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.QaService;
import com.unirag.retrieval.model.QaRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US2 租户隔离验收（spec.md SC-002、US2 场景 4）：同一问题分别向两租户提问，
 * 回答所依据的引用与提示词均不出本租户。
 *
 * <p>租户码全局唯一：{@code qaIsoA} / {@code qaIsoB}。
 */
class QaTenantIsolationIT extends AbstractRetrievalIT {

    private static final String TENANT_A = "qaIsoA";
    private static final String TENANT_B = "qaIsoB";
    private static final String TABLE_A = "embeddings_qaisoa_main";
    private static final String TABLE_B = "embeddings_qaisob_main";
    private static final String DOC_A = "qa-iso-doc-a";
    private static final String DOC_B = "qa-iso-doc-b";
    private static final String QUESTION = "向量数据库与租户隔离";

    @Autowired
    private QaService qaService;

    private OpenAiChatModel chatModelA;
    private OpenAiChatModel chatModelB;

    @BeforeEach
    void setUp() {
        chatModelA = prepare(TENANT_A, TABLE_A, DOC_A, "回答 A");
        chatModelB = prepare(TENANT_B, TABLE_B, DOC_B, "回答 B");
    }

    @Test
    void 同一问题分别提问时回答与引用均不出本租户() {
        QaService.QaResult fromA = ask(TENANT_A);
        QaService.QaResult fromB = ask(TENANT_B);

        assertThat(fromA.answer().answer()).isEqualTo("回答 A");
        assertThat(fromB.answer().answer()).isEqualTo("回答 B");
        assertThat(fromA.answer().citations()).isNotEmpty();
        assertThat(fromA.answer().citations()).allSatisfy(citation -> {
            assertThat(citation.docName()).isEqualTo(DOC_A);
            assertThat(citation.text()).isNotBlank();
        });
        assertThat(fromB.answer().citations()).allSatisfy(citation -> {
            assertThat(citation.docName()).isEqualTo(DOC_B);
            assertThat(citation.text()).isNotBlank();
        });
    }

    @Test
    void 各租户模型收到的提示词只含本租户片段() {
        ask(TENANT_A);
        ask(TENANT_B);

        String promptA = capturedPrompt(chatModelA);
        assertThat(promptA).contains(DOC_A).doesNotContain(DOC_B);
        String promptB = capturedPrompt(chatModelB);
        assertThat(promptB).contains(DOC_B).doesNotContain(DOC_A);
    }

    private OpenAiChatModel prepare(String tenantCode, String table, String docName, String answer) {
        cleanupTenant(tenantCode, table);
        cleanupObservations(tenantCode);
        long tenantId = insertTenant(tenantCode);
        insertStoreConfig(tenantId, "mainStore", table, 1024);
        insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(tenantCode, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(tenantCode, "mainStore")));
        ingestionService.ingest(new IngestRequest(
                tenantCode, docName, null, IngestionTestDocuments.longChineseDocument()));

        OpenAiChatModel model = stubChatModel(tenantCode);
        when(model.chat(any(ChatRequest.class))).thenReturn(chatResponse(answer));
        return model;
    }

    private QaService.QaResult ask(String tenantCode) {
        return inTenant(tenantCode, () -> qaService.ask(new QaRequest(QUESTION, null, 5, 0.0, null)));
    }

    private String capturedPrompt(OpenAiChatModel model) {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        return ((UserMessage) captor.getValue().messages().get(1)).singleText();
    }
}

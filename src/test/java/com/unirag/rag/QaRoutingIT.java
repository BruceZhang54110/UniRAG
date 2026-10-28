package com.unirag.rag;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.classify.RuleBasedQueryClassifier;
import com.unirag.rag.classify.ClassificationOutcome;
import com.unirag.rag.classify.QueryIntent;
import com.unirag.retrieval.AbstractRetrievalIT;
import com.unirag.retrieval.model.QaRequest;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** US1：三类问题通过统一问答入口真实执行，外部契约不变。 */
class QaRoutingIT extends AbstractRetrievalIT {

    private static final String TENANT = "qaRoutingUS1";
    private static final String TABLE = "embeddings_qaroutingus1_main";
    private static final String DOC = "qa-routing-doc-a";
    private static final String ANSWER = "基于资料的路由回答。[1]";

    @Autowired
    private QaService qaService;

    private final RuleBasedQueryClassifier classifier = new RuleBasedQueryClassifier();

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
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse(ANSWER));
    }

    @Test
    void 三类问题都通过现有问答契约返回且传统执行器可用() {
        assertAsk("什么是租户隔离？", QueryIntent.SIMPLE_FACT);
        assertAsk("向量数据库与租户隔离之间有什么关系？", QueryIntent.MULTI_HOP_RELATION);
        assertAsk("请先比较两种摄取方案，再结合知识库内容给出逐步实施建议。",
                QueryIntent.COMPLEX_REASONING);
    }

    private void assertAsk(String question, QueryIntent expectedIntent) {
        ClassificationOutcome outcome = classifier.classify(question);
        assertThat(outcome.intent()).isEqualTo(expectedIntent);

        QaService.QaResult result = inTenant(TENANT,
                () -> qaService.ask(new QaRequest(question, null, 5, 0.0, "routing-session")));

        assertThat(result.traceId()).matches("[0-9a-f]{32}");
        assertThat(result.answer().answer()).isEqualTo(ANSWER);
        assertThat(result.answer().degraded()).isFalse();
        assertThat(result.answer().citations()).isNotEmpty();
        assertThat(result.answer().citations()).allSatisfy(citation -> {
            assertThat(citation.docName()).isEqualTo(DOC);
            assertThat(citation.pipelineKey()).isEqualTo("main");
        });
    }
}

package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.QaService;
import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.stage.ChatGenerationStage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US2 验收（spec.md FR-004，contracts/retrieval-api.md §3，SC-001）：
 * 问答返回回答与引用清单，且**替身模型收到的提示词**确实包含检索片段与来源标注。
 *
 * <p>聊天模型以 Mockito 替身注入（不访问外网、不使用真实凭据）；断言覆盖提示词内容与
 * citations 顺序，而非仅断言方法被调用。租户码全局唯一：{@code qaUS2}。
 */
class QaServiceIT extends AbstractRetrievalIT {

    private static final String TENANT = "qaUS2";
    private static final String TABLE = "embeddings_qaus2_main";
    private static final String DOC = "qa-doc-a";
    private static final String QUESTION = "向量数据库与租户隔离有什么关系？";
    private static final String ANSWER = "根据资料，向量数据库需要在租户维度上隔离数据。[1][2]";

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
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse(ANSWER));
    }

    @Test
    void 回答与引用清单随响应返回() {
        QaService.QaResult result = ask();

        assertThat(result.traceId()).matches("[0-9a-f]{32}");
        assertThat(result.answer().answer()).isEqualTo(ANSWER);
        assertThat(result.answer().degraded()).isFalse();
        assertThat(result.answer().degradeReason()).isNull();
        assertThat(result.answer().citations()).isNotEmpty();
        assertThat(result.answer().citations()).allSatisfy(citation -> {
            assertThat(citation.docName()).isEqualTo(DOC);
            assertThat(citation.pipelineKey()).isEqualTo("main");
            assertThat(citation.text()).isNotBlank();
        });
    }

    @Test
    void 提示词包含检索片段与来源标注且标注顺序与引用清单一致() {
        QaService.QaResult result = ask();
        String prompt = capturedUserPrompt();

        List<RetrievedChunk> citations = result.answer().citations();
        int previousMarker = -1;
        for (int index = 0; index < citations.size(); index++) {
            RetrievedChunk citation = citations.get(index);
            String marker = "[" + (index + 1) + "] 来源：docName=" + citation.docName()
                    + "，pipelineKey=" + citation.pipelineKey()
                    + "，chunkIndex=" + citation.chunkIndex();

            int markerPosition = prompt.indexOf(marker);
            assertThat(markerPosition).as("提示词缺少第 %d 条引用标注", index + 1).isNotNegative();
            assertThat(markerPosition).as("引用标注顺序与 citations 不一致").isGreaterThan(previousMarker);
            assertThat(prompt).contains(citation.text());
            previousMarker = markerPosition;
        }
        assertThat(prompt).contains(QUESTION);
    }

    @Test
    void 只返回回答实际引用的片段且保持提示词顺序() {
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(chatResponse("只依据第二条资料作答。[2]"));

        QaService.QaResult result = ask();

        List<RetrievedChunk> citations = result.answer().citations();
        assertThat(citations).hasSize(1);
        RetrievedChunk citation = citations.get(0);
        assertThat(capturedUserPrompt()).contains(
                "[2] 来源：docName=" + citation.docName()
                        + "，pipelineKey=" + citation.pipelineKey()
                        + "，chunkIndex=" + citation.chunkIndex());
    }

    @Test
    void 系统提示词与生成参数按契约设置() {
        ask();
        ChatRequest request = capturedRequest();

        assertThat(request.messages()).hasSize(2);
        List<ChatMessage> messages = request.messages();
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messages.get(0)).text()).contains("资料片段");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(request.temperature()).isEqualTo(ChatGenerationStage.TEMPERATURE);
        // 1.20.0 的字段名是 maxOutputTokens（research.md R8）
        assertThat(request.maxOutputTokens()).isEqualTo(ChatGenerationStage.MAX_OUTPUT_TOKENS);
    }

    private QaService.QaResult ask() {
        return inTenant(TENANT, () -> qaService.ask(new QaRequest(QUESTION, null, 5, 0.0, "session-1")));
    }

    private ChatRequest capturedRequest() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        return captor.getValue();
    }

    private String capturedUserPrompt() {
        return ((UserMessage) capturedRequest().messages().get(1)).singleText();
    }
}

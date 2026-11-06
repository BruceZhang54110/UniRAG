package com.unirag.retrieval;

import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.QaService;
import com.unirag.retrieval.model.QaRequest;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.RetrievedChunk;
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
 * US2 端到端验收（spec.md US2 场景 1–4，FR-007/008，SC-003）：
 * 限定章节的检索命中全落在该章节子树；不存在章节返回明确空结果提示（不静默全库检索）；
 * 问答引用行含 section= 章节路径；不带 section 零回归；跨租户负向。
 * 租户码全局唯一：{@code saUS2} / {@code saIso}。
 */
class SectionFilterQaIT extends AbstractRetrievalIT {

    private static final String TENANT = "saUS2";
    private static final String TABLE = "embeddings_saus2_main";
    private static final String DOC = "sa-filter-doc";
    private static final String QUESTION = "生态协同建设有哪些具体工作？";
    private static final String ANSWER = "根据资料，生态协同包括开源专区与人才培育。[1]";

    private static final String CHAPTER = "二、重点任务";
    private static final String SUBSECTION = "二、重点任务/（三）生态协同建设";

    @Autowired
    private QaService qaService;

    @Autowired
    private RetrievalService retrievalService;

    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertStructureAwarePipeline(tenantId, "sa", 300, 30, 500, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new com.unirag.ingestion.DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.structureAwareDocument()));

        chatModel = stubChatModel(TENANT);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(chatResponse(ANSWER));
    }

    @Test
    void sectionFilteredRetrievalHitsOnlyWithinSubtree() {
        RetrievalService.RetrievalResult inTenant = inTenant(TENANT, () -> retrievalService.retrieve(
                new RetrievalRequest(QUESTION, null, null, 5, 0.0).withSection(CHAPTER)));
        assertThat(inTenant.emptyReason()).isNull();
        assertThat(inTenant.chunks()).isNotEmpty();
        for (RetrievedChunk chunk : inTenant.chunks()) {
            assertThat(chunk.sectionPath()).as("命中必须属于 %s 子树", CHAPTER)
                    .satisfies(path -> assertThat(path.equals(CHAPTER) || path.startsWith(CHAPTER + "/"))
                            .isTrue());
        }

        // 二级子节限定：命中全部精确属于该子节
        RetrievalService.RetrievalResult subTree = inTenant(TENANT, () -> retrievalService.retrieve(
                new RetrievalRequest(QUESTION, null, null, 5, 0.0).withSection(SUBSECTION)));
        assertThat(subTree.chunks()).isNotEmpty();
        assertThat(subTree.chunks()).allSatisfy(chunk ->
                assertThat(chunk.sectionPath()).isEqualTo(SUBSECTION));
    }

    @Test
    void unknownSectionReturnsExplicitEmptyResult() {
        RetrievalService.RetrievalResult result = inTenant(TENANT, () -> retrievalService.retrieve(
                new RetrievalRequest(QUESTION, null, null, 5, 0.0).withSection("九、不存在的章节")));
        assertThat(result.chunks()).isEmpty();
        assertThat(result.emptyReason()).isEqualTo(RetrievalService.EMPTY_REASON_SECTION_NOT_FOUND);
    }

    @Test
    void qaCitationShowsSectionPath() {
        QaService.QaResult result = inTenant(TENANT,
                () -> qaService.ask(new QaRequest(QUESTION, null, 5, 0.0, null)));
        assertThat(result.answer().citations()).isNotEmpty();
        String prompt = capturedUserPrompt();
        for (RetrievedChunk citation : result.answer().citations()) {
            assertThat(citation.sectionPath()).isNotBlank();
            assertThat(prompt).contains("section=" + citation.sectionPath());
        }
    }

    @Test
    void unfilteredRetrievalBehavesAsBefore() {
        RetrievalService.RetrievalResult result = inTenant(TENANT, () -> retrievalService.retrieve(
                new RetrievalRequest(QUESTION, null, null, 5, 0.0)));
        assertThat(result.emptyReason()).isNull();
        assertThat(result.chunks()).isNotEmpty();
        // 不限定时可命中前言以外的多个章节
        assertThat(result.chunks().stream().map(RetrievedChunk::sectionPath).distinct().count())
                .isGreaterThan(1);
    }

    @Test
    void crossTenantSectionDataInvisible() {
        String otherTenant = "saIso";
        cleanupTenant(otherTenant, "embeddings_saiso_main");
        long otherId = insertTenant(otherTenant);
        insertStoreConfig(otherId, "mainStore", "embeddings_saiso_main", 1024);
        insertStructureAwarePipeline(otherId, "sa", 300, 30, 500, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(otherTenant, Map.of("default", new com.unirag.ingestion.DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(otherTenant, "mainStore")));

        // 另一租户未摄取该文档：同样的章节限定 → 不存在（不得看见本租户的章节数据）
        RetrievalService.RetrievalResult result = inTenant(otherTenant, () -> retrievalService.retrieve(
                new RetrievalRequest(QUESTION, null, null, 5, 0.0).withSection(CHAPTER)));
        assertThat(result.chunks()).isEmpty();
        assertThat(result.emptyReason()).isEqualTo(RetrievalService.EMPTY_REASON_SECTION_NOT_FOUND);
    }

    private String capturedUserPrompt() {
        ArgumentCaptor<dev.langchain4j.model.chat.request.ChatRequest> captor =
                ArgumentCaptor.forClass(dev.langchain4j.model.chat.request.ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        return ((dev.langchain4j.data.message.UserMessage) captor.getValue().messages().get(1)).singleText();
    }
}

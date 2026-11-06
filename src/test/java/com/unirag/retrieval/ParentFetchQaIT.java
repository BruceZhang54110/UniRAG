package com.unirag.retrieval;

import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.rag.QaService;
import com.unirag.retrieval.model.QaRequest;
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
 * US2 端到端验收（spec.md US2 场景 1/2/3，FR-005/006/007/009/010，SC-002/005）：
 * 父子策略摄取后问答——prompt 上下文为父块全文、引用 chunkIndex=父块序号、同父块去重、
 * 观测落 PARENT_FETCH 行且明细无文本原文（原则 V）、父块缺失降级不中断问答。
 * 租户码全局唯一：{@code pfUS2} / {@code pfIso}。
 */
class ParentFetchQaIT extends AbstractRetrievalIT {

    private static final String TENANT = "pfUS2";
    private static final String TABLE = "embeddings_pfus2_main";
    private static final String DOC = "parent-fetch-doc";
    private static final String QUESTION = "嵌入模型与查询改写分别解决什么问题？";
    private static final String ANSWER = "根据资料，嵌入模型负责向量化，查询改写负责澄清意图。[1]";

    @Autowired
    private QaService qaService;

    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        // 预算放大到 2 个父块，保证截断行为可断言、prompt 可完整包含被引用父块
        insertParentChildPipeline(tenantId, "pc", 300, 30, 1200, "mainStore", "default");
        jdbcTemplate.update(
                "UPDATE ingestion_pipeline SET parent_context_max_chunks = 2, parent_context_max_tokens = 4000 "
                        + "WHERE tenant_id = ? AND pipeline_key = 'pc'", tenantId);
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new com.unirag.ingestion.DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));

        chatModel = stubChatModel(TENANT);
        when(chatModel.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(chatResponse(ANSWER));
    }

    @Test
    void prompt使用父块全文且引用指向父块序号() {
        QaService.QaResult result = ask();
        String prompt = capturedUserPrompt();

        List<RetrievedChunk> citations = result.answer().citations();
        assertThat(citations).isNotEmpty();
        long documentId = documentId();
        for (RetrievedChunk citation : citations) {
            // 引用的 chunkIndex 是父块序号，且 prompt 包含该父块全文（而非子块碎片）
            Map<String, Object> parentRow = jdbcTemplate.queryForMap(
                    "SELECT id, content FROM document_chunk WHERE document_id = ? AND pipeline_id = ? "
                            + "AND chunk_level = 'PARENT' AND seq_index = ?",
                    documentId, pipelineId(TENANT, "pc"), citation.chunkIndex());
            assertThat(citation.text()).isEqualTo(parentRow.get("content"));
            assertThat(prompt).contains((String) parentRow.get("content"));
        }
        // SC-002：同一父块在上下文中只出现一次（去重 100%）
        assertThat(citations.stream().map(RetrievedChunk::chunkIndex).distinct().count())
                .isEqualTo(citations.size());
    }

    @Test
    void 观测落PARENT_FETCH行且明细不含文本原文() {
        QaService.QaResult result = ask();

        Map<String, Object> observation = jdbcTemplate.queryForMap(
                "SELECT status, hit_count, item_count, detail FROM retrieval_observation "
                        + "WHERE tenant_id = ? AND trace_id = ? AND stage = 'PARENT_FETCH'",
                tenantId(TENANT), result.traceId());
        assertThat(observation.get("status")).isIn("SUCCESS", "DEGRADED");
        assertThat((Integer) observation.get("hit_count")).isGreaterThan(0);
        assertThat((Integer) observation.get("item_count")).isGreaterThan(0);

        String parentContentPrefix = jdbcTemplate.queryForObject(
                "SELECT left(content, 40) FROM document_chunk WHERE document_id = ? AND chunk_level = 'PARENT' LIMIT 1",
                String.class, documentId());
        assertThat((String) observation.get("detail")).doesNotContain(parentContentPrefix);
    }

    @Test
    void 父块缺失降级子块文本且问答不中断() {
        // 制造数据异常：删父块行、保留子块与向量
        jdbcTemplate.update(
                "DELETE FROM document_chunk WHERE document_id = ? AND chunk_level = 'PARENT'", documentId());

        QaService.QaResult result = ask();
        assertThat(result.answer().answer()).isEqualTo(ANSWER);
        assertThat(result.answer().citations()).isNotEmpty();

        Map<String, Object> observation = jdbcTemplate.queryForMap(
                "SELECT status, error_message FROM retrieval_observation "
                        + "WHERE tenant_id = ? AND trace_id = ? AND stage = 'PARENT_FETCH'",
                tenantId(TENANT), result.traceId());
        assertThat(observation.get("status")).isEqualTo("DEGRADED");
        assertThat((String) observation.get("error_message")).contains("PARENT_MISSING");
    }

    @Test
    void 跨租户不可见父块上下文() {
        String otherTenant = "pfIso";
        cleanupTenant(otherTenant, "embeddings_pfiso_main");
        cleanupObservations(otherTenant);
        long otherId = insertTenant(otherTenant);
        insertStoreConfig(otherId, "mainStore", "embeddings_pfiso_main", 1024);
        insertPipeline(otherId, "main", 500, 50, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(otherTenant, Map.of("default", new com.unirag.ingestion.DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(otherTenant, "mainStore")));
        OpenAiChatModel otherChat = stubChatModel(otherTenant);
        when(otherChat.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(chatResponse("无相关资料。"));

        // 以另一租户身份问同一问题：检索不到本租户文档 → 无引用、走无知识降级（宪法 IV）
        QaService.QaResult result = inTenant(otherTenant,
                () -> qaService.ask(new QaRequest(QUESTION, null, 5, 0.0, null)));
        assertThat(result.answer().citations()).isEmpty();
        assertThat(result.answer().answer()).isNotBlank();
        // 本租户父块内容不出现在任何可观察面：观测表无对方租户的 PARENT_FETCH 引用明细
        Long leaked = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM retrieval_observation o JOIN tenant t ON t.id = o.tenant_id "
                        + "WHERE t.tenant_code = ? AND o.stage = 'PARENT_FETCH' AND o.item_count > 0",
                Long.class, otherTenant);
        assertThat(leaked).isZero();
    }

    private QaService.QaResult ask() {
        return inTenant(TENANT, () -> qaService.ask(new QaRequest(QUESTION, null, 5, 0.0, null)));
    }

    private long documentId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE tenant_id = ? AND doc_name = ?",
                Long.class, tenantId(TENANT), DOC);
    }

    private String capturedUserPrompt() {
        ArgumentCaptor<dev.langchain4j.model.chat.request.ChatRequest> captor =
                ArgumentCaptor.forClass(dev.langchain4j.model.chat.request.ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        return ((dev.langchain4j.data.message.UserMessage) captor.getValue().messages().get(1)).singleText();
    }
}

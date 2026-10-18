package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 验收（spec.md FR-001/FR-002/FR-003，SC-001）：检索服务本体的行为。
 *
 * <p>确定性嵌入替身 + 真实 PostgreSQL/pgvector；断言覆盖向量检索结果与既有持久化行的对应关系，
 * 而非仅方法调用。租户码全局唯一：{@code retrUS1}。
 */
class RetrievalServiceIT extends AbstractRetrievalIT {

    private static final String TENANT = "retrUS1";
    private static final String TABLE = "embeddings_retrus1_main";
    private static final String PIPELINE = "main";
    private static final String DOC = "retrieval-doc-a";
    private static final String DOC_SHORT = "retrieval-doc-b";

    @Autowired
    private RetrievalService retrievalService;

    private long documentId;
    private long shortDocumentId;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertPipeline(tenantId, PIPELINE, 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));

        documentId = ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocument())).documentId();
        shortDocumentId = ingestionService.ingest(new IngestRequest(
                TENANT, DOC_SHORT, null, IngestionTestDocuments.shortChineseDocument())).documentId();
    }

    @Test
    void topK与分数降序与来源元数据完整() {
        RetrievalService.RetrievalResult result = retrieve(new RetrievalRequest(
                "向量数据库与检索增强生成", null, null, 3, 0.0));

        assertThat(result.traceId()).matches("[0-9a-f]{32}");
        assertThat(result.searchedTables()).isEqualTo(1);
        assertThat(result.failedTables()).isEmpty();
        assertThat(result.chunks()).hasSize(3);
        assertThat(result.chunks())
                .isSortedAccordingTo(Comparator.comparingDouble(RetrievedChunk::score).reversed());

        long pipelineId = pipelineId(TENANT, PIPELINE);
        for (RetrievedChunk chunk : result.chunks()) {
            assertThat(chunk.docName()).isIn(DOC, DOC_SHORT);
            assertThat(chunk.pipelineKey()).isEqualTo(PIPELINE);
            assertThat(chunk.text()).isNotBlank();
            String persisted = jdbcTemplate.queryForObject(
                    "SELECT content FROM document_chunk WHERE document_id = ? AND pipeline_id = ? AND seq_index = ?",
                    String.class, chunk.documentId(), pipelineId, chunk.chunkIndex());
            assertThat(chunk.text()).isEqualTo(persisted);
        }
    }

    @Test
    void 同输入结果逐字一致() {
        RetrievalRequest request = new RetrievalRequest("租户隔离与可观测的执行记录", null, null, 5, 0.0);

        List<RetrievedChunk> first = retrieve(request).chunks();
        List<RetrievedChunk> second = retrieve(request).chunks();

        assertThat(first).isNotEmpty();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void documentId过滤只返回该文档片段() {
        RetrievalService.RetrievalResult result = retrieve(new RetrievalRequest(
                "向量数据库", null, shortDocumentId, 5, 0.0));

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.documentId()).isEqualTo(shortDocumentId);
            assertThat(chunk.docName()).isEqualTo(DOC_SHORT);
        });
    }

    @Test
    void 不属于本租户的文档标识返回空结果而不报错() {
        RetrievalService.RetrievalResult result = retrieve(new RetrievalRequest("向量数据库", null, -1L, 5, 0.0));

        assertThat(result.chunks()).isEmpty();
        assertThat(result.failedTables()).isEmpty();
    }

    @Test
    void minScore在库内生效且返回条数可少于topK() {
        String chunkText = jdbcTemplate.queryForObject(
                "SELECT content FROM document_chunk WHERE document_id = ? AND pipeline_id = ? AND seq_index = 0",
                String.class, documentId, pipelineId(TENANT, PIPELINE));

        List<RetrievedChunk> all = retrieve(new RetrievalRequest(chunkText, null, null, 50, 0.0)).chunks();
        List<RetrievedChunk> filtered = retrieve(new RetrievalRequest(chunkText, null, null, 50, 0.99)).chunks();

        assertThat(all).hasSizeGreaterThan(1);
        assertThat(filtered).isNotEmpty().hasSizeLessThan(all.size());
        assertThat(filtered).allSatisfy(chunk -> assertThat(chunk.score()).isGreaterThanOrEqualTo(0.99));
    }

    private RetrievalService.RetrievalResult retrieve(RetrievalRequest request) {
        return inTenant(TENANT, () -> retrievalService.retrieve(request));
    }
}

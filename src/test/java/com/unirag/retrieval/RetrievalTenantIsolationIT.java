package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 租户隔离验收（spec.md SC-002、US1 场景 2）：两租户拥有相似主题文档，同一查询互不可见。
 *
 * <p>租户码全局唯一：{@code retrIsoA} / {@code retrIsoB}。
 */
class RetrievalTenantIsolationIT extends AbstractRetrievalIT {

    private static final String TENANT_A = "retrIsoA";
    private static final String TENANT_B = "retrIsoB";
    private static final String TABLE_A = "embeddings_retrisoa_main";
    private static final String TABLE_B = "embeddings_retrisob_main";
    private static final String DOC_A = "iso-doc-a";
    private static final String DOC_B = "iso-doc-b";
    private static final String QUERY = "向量数据库与租户隔离";

    @Autowired
    private RetrievalService retrievalService;

    private long documentIdA;
    private long documentIdB;

    @BeforeEach
    void setUp() {
        documentIdA = prepare(TENANT_A, TABLE_A, DOC_A);
        documentIdB = prepare(TENANT_B, TABLE_B, DOC_B);
    }

    @Test
    void 同一查询各自只返回本租户片段() {
        List<RetrievedChunk> fromA = retrieve(TENANT_A, new RetrievalRequest(QUERY, null, null, 10, 0.0));
        List<RetrievedChunk> fromB = retrieve(TENANT_B, new RetrievalRequest(QUERY, null, null, 10, 0.0));

        assertThat(fromA).isNotEmpty();
        assertThat(fromB).isNotEmpty();
        assertThat(fromA).allSatisfy(chunk -> {
            assertThat(chunk.documentId()).isEqualTo(documentIdA);
            assertThat(chunk.docName()).isEqualTo(DOC_A);
        });
        assertThat(fromB).allSatisfy(chunk -> {
            assertThat(chunk.documentId()).isEqualTo(documentIdB);
            assertThat(chunk.docName()).isEqualTo(DOC_B);
        });
        assertThat(fromA).noneSatisfy(chunk -> assertThat(chunk.documentId()).isEqualTo(documentIdB));
    }

    @Test
    void 用他租户的文档标识过滤不泄漏存在性() {
        List<RetrievedChunk> leaked = retrieve(TENANT_A, new RetrievalRequest(QUERY, null, documentIdB, 10, 0.0));

        assertThat(leaked).isEmpty();
    }

    @Test
    void 跨租户泄漏用例为零() {
        for (String tenantCode : List.of(TENANT_A, TENANT_B)) {
            long ownDocument = tenantCode.equals(TENANT_A) ? documentIdA : documentIdB;
            String ownDocName = tenantCode.equals(TENANT_A) ? DOC_A : DOC_B;
            List<RetrievedChunk> chunks = retrieve(tenantCode, new RetrievalRequest(QUERY, null, null, 10, 0.0));

            assertThat(chunks).isNotEmpty();
            assertThat(chunks).allSatisfy(chunk -> {
                assertThat(chunk.documentId()).isEqualTo(ownDocument);
                assertThat(chunk.docName()).isEqualTo(ownDocName);
            });
        }
    }

    private long prepare(String tenantCode, String table, String docName) {
        cleanupTenant(tenantCode, table);
        cleanupObservations(tenantCode);
        long tenantId = insertTenant(tenantCode);
        insertStoreConfig(tenantId, "mainStore", table, 1024);
        insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(tenantCode, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(tenantCode, "mainStore")));
        return ingestionService.ingest(new IngestRequest(
                tenantCode, docName, null, IngestionTestDocuments.longChineseDocument())).documentId();
    }

    private List<RetrievedChunk> retrieve(String tenantCode, RetrievalRequest request) {
        return inTenant(tenantCode, () -> retrievalService.retrieve(request)).chunks();
    }
}

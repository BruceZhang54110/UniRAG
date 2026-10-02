package com.unirag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US2 验收（spec.md FR-001/004，contracts §2，SC-003）：
 * 粗/细双粒度一次调用产出两表（粗片段数 < 细片段数、粗平均 token > 细）、
 * 每表 metadata pipeline_key 各为其 key、一次调用 outcome.runs 含两条；
 * 一管线指向不存在的资源 → 该管线 FAILED（原因含 key）且其余管线照常 SUCCESS、文档 PARTIAL、互不干扰；
 * 双管线不同维度（1024/768）各表维度正确、数据互不串。
 * 租户码全局唯一：{@code dualUS2} / {@code dimUS2}。
 */
class DualGranularityIngestionIT extends AbstractIngestionIT {

    private static final String TENANT = "dualUS2";
    private static final String COARSE_TABLE = "embeddings_dualus2_coarse";
    private static final String FINE_TABLE = "embeddings_dualus2_fine";
    private static final String DOC = "dual-granularity-doc";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, COARSE_TABLE, FINE_TABLE);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "coarseStore", COARSE_TABLE, 1024);
        insertStoreConfig(tenantId, "fineStore", FINE_TABLE, 1024);
        insertPipeline(tenantId, "coarse", 500, 50, "coarseStore", "default");
        insertPipeline(tenantId, "fine", 150, 20, "fineStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("coarseStore", storeBean(TENANT, "coarseStore"),
                        "fineStore", storeBean(TENANT, "fineStore")));
    }

    @Test
    void scenario1_dualGranularitySingleCall() throws Exception {
        IngestOutcome outcome = ingestionService.ingest(
                new IngestRequest(TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));

        // 一次调用两条管线各自执行（FR-001）
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.INGESTED);
        assertThat(outcome.runs()).hasSize(2);
        Map<String, RunOutcome> runsByKey = outcome.runs().stream()
                .collect(Collectors.toMap(RunOutcome::pipelineKey, Function.identity()));
        assertThat(runsByKey.keySet()).containsExactlyInAnyOrder("coarse", "fine");
        assertThat(runsByKey.values()).allSatisfy(r -> assertThat(r.status()).isEqualTo(RunStatus.SUCCESS));

        // 验收场景 1：两表均有数据；粗表片段数 < 细表片段数；粗表平均 token > 细表
        long tenantId = tenantId(TENANT);
        long documentId = outcome.documentId();
        assertPipelineBacklinked(tenantId, documentId, "coarse", COARSE_TABLE, 500);
        assertPipelineBacklinked(tenantId, documentId, "fine", FINE_TABLE, 150);

        int coarseChunks = runsByKey.get("coarse").chunkCount();
        int fineChunks = runsByKey.get("fine").chunkCount();
        assertThat(coarseChunks).isGreaterThan(1);
        assertThat(fineChunks).isGreaterThan(coarseChunks);

        Double avgCoarse = jdbcTemplate.queryForObject(
                "SELECT avg(token_count) FROM document_chunk WHERE document_id = ? AND pipeline_id = ?",
                Double.class, documentId, pipelineId(tenantId, "coarse"));
        Double avgFine = jdbcTemplate.queryForObject(
                "SELECT avg(token_count) FROM document_chunk WHERE document_id = ? AND pipeline_id = ?",
                Double.class, documentId, pipelineId(tenantId, "fine"));
        assertThat(avgCoarse).isGreaterThan(avgFine);
    }

    @Test
    void scenario3_brokenPipelineFailsIsolated() {
        // 第三条管线指向不存在的 EMBEDDING_STORE key
        long tenantId = tenantId(TENANT);
        insertPipeline(tenantId, "brokenUS2", 500, 50, "ghostStore", "default");
        stubMissingStore(TENANT, "ghostStore");

        IngestOutcome outcome = ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocumentRevised()));

        // 验收场景 3：broken 管线 FAILED（原因含 key），coarse/fine 照常 SUCCESS，文档 PARTIAL
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.PARTIAL);
        assertThat(outcome.runs()).hasSize(3);
        Map<String, RunOutcome> runsByKey = outcome.runs().stream()
                .collect(Collectors.toMap(RunOutcome::pipelineKey, Function.identity()));
        RunOutcome broken = runsByKey.get("brokenUS2");
        assertThat(broken.status()).isEqualTo(RunStatus.FAILED);
        assertThat(broken.errorMessage()).contains("ghostStore");
        assertThat(runsByKey.get("coarse").status()).isEqualTo(RunStatus.SUCCESS);
        assertThat(runsByKey.get("fine").status()).isEqualTo(RunStatus.SUCCESS);

        // 三表状态互不干扰：broken 无 chunk 行；coarse/fine 行数 = 新内容片段数（先清后写替换）
        Long brokenChunks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE pipeline_id = ?", Long.class,
                pipelineId(tenantId, "brokenUS2"));
        assertThat(brokenChunks).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + COARSE_TABLE, Long.class))
                .isEqualTo((long) runsByKey.get("coarse").chunkCount());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + FINE_TABLE, Long.class))
                .isEqualTo((long) runsByKey.get("fine").chunkCount());

        Map<String, Object> documentRow = jdbcTemplate.queryForMap(
                "SELECT status FROM document WHERE id = ?", outcome.documentId());
        assertThat(documentRow.get("status")).isEqualTo("PARTIAL");
        // 失败原因已入 ingestion_run（可查，FR-004）
        Long failedRuns = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ingestion_run WHERE document_id = ? AND status = 'FAILED' AND error_message LIKE '%ghostStore%'",
                Long.class, outcome.documentId());
        assertThat(failedRuns).isEqualTo(1L);
        // 恢复本租户仅 coarse/fine 两条启用管线，保证用例间顺序无关（scenario1 断言两条管线全量成功）
        jdbcTemplate.update(
                "UPDATE ingestion_pipeline SET enabled = FALSE WHERE tenant_id = ? AND pipeline_key = 'brokenUS2'",
                tenantId);
    }

    @Test
    void scenario2_dimensionPerPipelineIsolated() throws Exception {
        // 独立租户 dimUS2：粗/细管线各配独立模型 key（不同维度）与独立向量表
        String dimTenant = "dimUS2";
        String dimCoarseTable = "embeddings_dimus2_coarse";
        String dimFineTable = "embeddings_dimus2_fine";
        cleanupTenant(dimTenant, dimCoarseTable, dimFineTable);
        long tenantId = insertTenant(dimTenant);
        insertStoreConfig(tenantId, "coarseStore", dimCoarseTable, 1024);
        insertStoreConfig(tenantId, "fineStore", dimFineTable, 768);
        insertPipeline(tenantId, "coarse", 500, 50, "coarseStore", "m1024");
        insertPipeline(tenantId, "fine", 150, 20, "fineStore", "m768");
        registrar.loadAndRegister();
        stubRouter(dimTenant,
                Map.of("m1024", new DeterministicEmbeddingModel(1024),
                        "m768", new DeterministicEmbeddingModel(768)),
                Map.of("coarseStore", storeBean(dimTenant, "coarseStore"),
                        "fineStore", storeBean(dimTenant, "fineStore")));

        IngestOutcome outcome = ingestionService.ingest(
                new IngestRequest(dimTenant, DOC, null, IngestionTestDocuments.longChineseDocument()));

        // 验收场景 2：各表向量维度与各自管线声明的模型输出一致
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.INGESTED);
        Integer coarseDim = jdbcTemplate.queryForObject(
                "SELECT vector_dims(embedding) FROM " + dimCoarseTable + " LIMIT 1", Integer.class);
        Integer fineDim = jdbcTemplate.queryForObject(
                "SELECT vector_dims(embedding) FROM " + dimFineTable + " LIMIT 1", Integer.class);
        assertThat(coarseDim).isEqualTo(1024);
        assertThat(fineDim).isEqualTo(768);

        // 数据互不串：每表 metadata pipeline_key 对应、chunk 行数与向量行数一致
        assertPipelineBacklinked(tenantId, outcome.documentId(), "coarse", dimCoarseTable, 500);
        assertPipelineBacklinked(tenantId, outcome.documentId(), "fine", dimFineTable, 150);
    }

    /** 回链双向一致断言（contracts §4）：chunk 行数 = 向量表行数，逐片 embedding_id 可回查且 metadata 四键匹配。 */
    private void assertPipelineBacklinked(long tenantId, long documentId, String pipelineKey,
                                          String vectorTable, int maxTokens) throws Exception {
        Long pipelineId = pipelineId(tenantId, pipelineKey);
        Long chunkRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND pipeline_id = ?", Long.class,
                documentId, pipelineId);
        Long vectorRows = jdbcTemplate.queryForObject("SELECT count(*) FROM " + vectorTable, Long.class);
        assertThat(chunkRows).as("%s 表行数与切片行数一致", pipelineKey).isEqualTo(vectorRows);
        assertThat(chunkRows).isGreaterThan(0);

        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(
                "SELECT seq_index, embedding_id, token_count FROM document_chunk "
                        + "WHERE document_id = ? AND pipeline_id = ? ORDER BY seq_index", documentId, pipelineId);
        for (Map<String, Object> chunk : chunks) {
            String embeddingId = (String) chunk.get("embedding_id");
            int tokenCount = ((Number) chunk.get("token_count")).intValue();
            assertThat(embeddingId).isNotBlank();
            assertThat(tokenCount).isPositive().isLessThanOrEqualTo(maxTokens);
            String metadataJson = jdbcTemplate.queryForObject(
                    "SELECT metadata FROM " + vectorTable + " WHERE embedding_id = ?::uuid", String.class, embeddingId);
            JsonNode metadata = OBJECT_MAPPER.readTree(metadataJson);
            assertThat(metadataKeys(metadata))
                    .containsExactlyInAnyOrder("doc_name", "document_id", "pipeline_key", "chunk_index");
            assertThat(metadata.get("pipeline_key").asText()).isEqualTo(pipelineKey);
            assertThat(metadata.get("document_id").asLong()).isEqualTo(documentId);
            assertThat(metadata.get("chunk_index").asInt())
                    .isEqualTo(((Number) chunk.get("seq_index")).intValue());
            assertThat(metadata.get("doc_name").asText()).isEqualTo(DOC);
        }
    }

    private long tenantId(String tenantCode) {
        return jdbcTemplate.queryForObject("SELECT id FROM tenant WHERE tenant_code = ?", Long.class, tenantCode);
    }

    private long pipelineId(long tenantId, String pipelineKey) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ingestion_pipeline WHERE tenant_id = ? AND pipeline_key = ?",
                Long.class, tenantId, pipelineKey);
    }

    private static Set<String> metadataKeys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}

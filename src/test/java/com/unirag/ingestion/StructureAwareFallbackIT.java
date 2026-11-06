package com.unirag.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 场景 3 / FR-005 / FR-009 / SC-005 验收：无标题文档回退为递归等价单级产出
 * （仅 CHILD 行、section_path NULL、块数与 RECURSIVE 逐字段一致、ingestion_run.note 标注）；
 * 结构感知数据重跑替换语义生效。
 * 租户码全局唯一：{@code saFb}。
 */
class StructureAwareFallbackIT extends AbstractIngestionIT {

    private static final String TENANT = "saFb";
    private static final String SA_TABLE = "embeddings_safb_sa";
    private static final String SINGLE_TABLE = "embeddings_safb_single";

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, SA_TABLE, SINGLE_TABLE);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "saStore", SA_TABLE, 1024);
        insertStoreConfig(tenantId, "singleStore", SINGLE_TABLE, 1024);
        insertStructureAwarePipeline(tenantId, "sa", 300, 30, 1200, "saStore", "default");
        insertPipeline(tenantId, "single", 300, 30, "singleStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("saStore", storeBean(TENANT, "saStore"),
                        "singleStore", storeBean(TENANT, "singleStore")));
    }

    @Test
    void noHeadingDocumentFallsBackToRecursiveEquivalent() {
        IngestOutcome outcome = ingestionService.ingest(
                new IngestRequest(TENANT, "no-heading", null, IngestionTestDocuments.noHeadingDocument()));
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.INGESTED);

        long tenantId = tenantId();
        long documentId = outcome.documentId();
        long saPipeline = pipelineId(tenantId, "sa");
        long singlePipeline = pipelineId(tenantId, "single");

        // 回退：仅 CHILD 行、无 PARENT 行、section_path 全 NULL
        assertThat(countLevel(documentId, saPipeline, "PARENT")).isZero();
        Long saChildren = countLevel(documentId, saPipeline, "CHILD");
        Long singleChunks = countLevel(documentId, singlePipeline, "CHILD");
        assertThat(saChildren).isGreaterThan(0).isEqualTo(singleChunks);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND pipeline_id = ? "
                        + "AND section_path IS NOT NULL", Long.class, documentId, saPipeline)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + SA_TABLE, Long.class)).isEqualTo(saChildren);

        // 逐字节等价：两管线的子块内容序列一致
        List<String> saTexts = jdbcTemplate.queryForList(
                "SELECT content FROM document_chunk WHERE document_id = ? AND pipeline_id = ? "
                        + "AND chunk_level = 'CHILD' ORDER BY seq_index", String.class, documentId, saPipeline);
        List<String> singleTexts = jdbcTemplate.queryForList(
                "SELECT content FROM document_chunk WHERE document_id = ? AND pipeline_id = ? "
                        + "AND chunk_level = 'CHILD' ORDER BY seq_index", String.class, documentId, singlePipeline);
        assertThat(saTexts).containsExactlyElementsOf(singleTexts);

        // 回退标注（FR-005）
        String note = jdbcTemplate.queryForObject(
                "SELECT note FROM ingestion_run WHERE document_id = ? AND pipeline_id = ? ORDER BY id DESC LIMIT 1",
                String.class, documentId, saPipeline);
        assertThat(note).isEqualTo("STRUCTURE_FALLBACK_NO_HEADING");
    }

    @Test
    void reIngestReplacesStructureAwareData() {
        ingestionService.ingest(new IngestRequest(
                TENANT, "heading-doc", null, IngestionTestDocuments.structureAwareDocument()));
        long documentId = jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE tenant_id = ? AND doc_name = 'heading-doc'",
                Long.class, tenantId());

        List<Long> oldParentIds = jdbcTemplate.queryForList(
                "SELECT id FROM document_chunk WHERE document_id = ? AND chunk_level = 'PARENT'",
                Long.class, documentId);
        assertThat(oldParentIds).isNotEmpty();

        IngestOutcome second = ingestionService.ingest(new IngestRequest(
                TENANT, "heading-doc", null, IngestionTestDocuments.structureAwareDocument() + "修订附则。\n\n"));
        assertThat(second.status()).isEqualTo(IngestOutcome.Status.INGESTED);

        for (Long oldId : oldParentIds) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM document_chunk WHERE id = ?", Long.class, oldId)).isZero();
        }
        Long orphans = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk c WHERE c.document_id = ? AND c.chunk_level = 'CHILD' "
                        + "AND c.parent_chunk_id IS NOT NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM document_chunk p WHERE p.id = c.parent_chunk_id)",
                Long.class, documentId);
        assertThat(orphans).as("替换后不得残留孤儿子块").isZero();
    }

    private long tenantId() {
        return jdbcTemplate.queryForObject("SELECT id FROM tenant WHERE tenant_code = ?", Long.class, TENANT);
    }

    private long pipelineId(long tenantId, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ingestion_pipeline WHERE tenant_id = ? AND pipeline_key = ?",
                Long.class, tenantId, key);
    }

    private Long countLevel(long documentId, long pipelineId, String level) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND pipeline_id = ? AND chunk_level = ?",
                Long.class, documentId, pipelineId, level);
    }
}

package com.unirag.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 场景 3 验收（spec.md FR-008）：父子策略重跑管线"先清后写"——
 * 旧父子 chunk 行与旧向量被完全替换，不存在 parent 已删而子块残留的孤儿数据。
 * 租户码全局唯一：{@code pcRep}。
 */
class PipelineExecutorParentReplaceIT extends AbstractIngestionIT {

    private static final String TENANT = "pcRep";
    private static final String TABLE = "embeddings_pcrep_main";
    private static final String DOC = "parent-replace-doc";

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertParentChildPipeline(tenantId, "pc", 300, 30, 1200, "mainStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
    }

    @Test
    void reIngestReplacesParentAndChildRowsAndVectors() {
        ingestionService.ingest(new IngestRequest(TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));
        long documentId = jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?",
                Long.class, TENANT, DOC);

        List<Long> oldParentIds = jdbcTemplate.queryForList(
                "SELECT id FROM document_chunk WHERE document_id = ? AND chunk_level = 'PARENT'", Long.class, documentId);
        List<Long> oldChildIds = jdbcTemplate.queryForList(
                "SELECT id FROM document_chunk WHERE document_id = ? AND chunk_level = 'CHILD'", Long.class, documentId);
        List<String> oldEmbeddingIds = jdbcTemplate.queryForList(
                "SELECT embedding_id FROM document_chunk WHERE document_id = ? AND chunk_level = 'CHILD' "
                        + "AND embedding_id IS NOT NULL", String.class, documentId);
        assertThat(oldParentIds).isNotEmpty();
        assertThat(oldChildIds).isNotEmpty();
        assertThat(oldEmbeddingIds).isNotEmpty();

        // 指纹变化触发替换（版本 +1，管线内先清后写）
        IngestOutcome second = ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocumentRevised()));
        assertThat(second.status()).isEqualTo(IngestOutcome.Status.INGESTED);

        // 旧父子行全部清除
        for (Long oldParentId : oldParentIds) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM document_chunk WHERE id = ?", Long.class, oldParentId)).isZero();
        }
        for (Long oldChildId : oldChildIds) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM document_chunk WHERE id = ?", Long.class, oldChildId)).isZero();
        }

        // 无孤儿：每个现存子块的 parent_chunk_id 都能 join 到现存父块
        Long orphans = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk c WHERE c.document_id = ? AND c.chunk_level = 'CHILD' "
                        + "AND NOT EXISTS (SELECT 1 FROM document_chunk p WHERE p.id = c.parent_chunk_id)",
                Long.class, documentId);
        assertThat(orphans).as("不允许存在父块已删而子块残留的孤儿数据").isZero();

        // 向量完整替换：行数 = 新子块行数，且旧 embedding_id 全部消失
        Long newChildRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND chunk_level = 'CHILD'",
                Long.class, documentId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE, Long.class)).isEqualTo(newChildRows);
        for (String oldEmbeddingId : oldEmbeddingIds) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + TABLE + " WHERE embedding_id = ?::uuid", Long.class, oldEmbeddingId))
                    .as("旧向量 %s 应已被替换删除", oldEmbeddingId)
                    .isZero();
        }

        // run 记录：两次 SUCCESS 均在（观测可查）
        Long runs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ingestion_run WHERE document_id = ? AND status = 'SUCCESS'",
                Long.class, documentId);
        assertThat(runs).isGreaterThanOrEqualTo(2);
    }
}

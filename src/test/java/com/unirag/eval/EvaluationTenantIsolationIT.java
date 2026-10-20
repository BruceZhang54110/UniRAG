package com.unirag.eval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 评测运行不得改动非评测租户数据（013 SC-005）。 */
@Tag("eval")
class EvaluationTenantIsolationIT extends AbstractEvaluationIT {

    private static final String SENTINEL_TENANT = "isoSentinel013";

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanupSentinel() {
        Long tenantId = tenantId();
        if (tenantId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id IN "
                + "(SELECT id FROM document WHERE tenant_id = ?)", tenantId);
        jdbcTemplate.update("DELETE FROM document WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM ingestion_pipeline WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_resource_config WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant WHERE id = ?", tenantId);
    }

    @Test
    void evaluationLeavesNonEvalTenantUntouched() throws Exception {
        setupSentinel();
        Map<String, Object> before = snapshot();

        EvaluationOptions options = new EvaluationOptions(
                "sample", RunMode.REPLAY,
                Path.of("eval/datasets/sample"),
                tempDir.resolve("reports"),
                tempDir.resolve("baselines"),
                false, null, null, null,
                Path.of("eval/fixtures/sample/embeddings.json"));

        runner().run(options, new EvaluationHarnessIT.DeterministicEmbeddingModel(8));

        assertEquals(before, snapshot());
    }

    private void setupSentinel() {
        cleanupSentinel();
        Long tenantId = jdbcTemplate.queryForObject("""
                INSERT INTO tenant (tenant_code, name) VALUES (?, ?)
                RETURNING id""", Long.class, SENTINEL_TENANT, "isolation sentinel");
        Long pipelineId = jdbcTemplate.queryForObject("""
                INSERT INTO ingestion_pipeline
                    (tenant_id, pipeline_key, embedding_store_key, embedding_model_key)
                VALUES (?, 'main', 'sentinel-store', 'sentinel-model')
                RETURNING id""", Long.class, tenantId);
        Long documentId = jdbcTemplate.queryForObject("""
                INSERT INTO document (tenant_id, doc_name, content_fingerprint, status)
                VALUES (?, 'sentinel-doc', 'sentinel-fingerprint', 'INGESTED')
                RETURNING id""", Long.class, tenantId);
        jdbcTemplate.update("""
                INSERT INTO document_chunk
                    (document_id, pipeline_id, seq_index, content, token_count)
                VALUES (?, ?, 0, 'sentinel content', 2)""", documentId, pipelineId);
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Long tenantId = tenantId();
        snapshot.put("tenantExists", tenantId != null);
        if (tenantId == null) {
            return snapshot;
        }
        snapshot.put("tenantEnabled", jdbcTemplate.queryForObject(
                "SELECT enabled FROM tenant WHERE id = ?", Boolean.class, tenantId));
        snapshot.put("documentCount", count(
                "SELECT count(*) FROM document WHERE tenant_id = ?", tenantId));
        snapshot.put("chunkCount", count(
                "SELECT count(*) FROM document_chunk WHERE document_id IN "
                        + "(SELECT id FROM document WHERE tenant_id = ?)", tenantId));
        snapshot.put("pipelineCount", count(
                "SELECT count(*) FROM ingestion_pipeline WHERE tenant_id = ?", tenantId));
        snapshot.put("resourceCount", count(
                "SELECT count(*) FROM tenant_resource_config WHERE tenant_id = ?", tenantId));
        snapshot.put("fingerprint", jdbcTemplate.queryForObject("""
                SELECT COALESCE(string_agg(doc_name || ':' || content_fingerprint, ',' ORDER BY id), '')
                FROM document WHERE tenant_id = ?""", String.class, tenantId));
        return snapshot;
    }

    private Long tenantId() {
        return jdbcTemplate.query("SELECT id FROM tenant WHERE tenant_code = ?",
                rs -> rs.next() ? rs.getLong(1) : null, SENTINEL_TENANT);
    }

    private Long count(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Long.class, argument);
    }
}

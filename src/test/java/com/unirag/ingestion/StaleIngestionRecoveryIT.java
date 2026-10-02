package com.unirag.ingestion;

import com.unirag.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 启动恢复验收（research.md R6，spec.md 边界情况）：滞留 INGESTING 超过 5 分钟的文档
 * 与其未完结 run 被标记 FAILED("interrupted")；未滞留文档不受影响；已完结 run 不被误改。
 * 租户码全局唯一：{@code recUS3}。
 */
class StaleIngestionRecoveryIT extends AbstractPostgresIntegrationTest {

    private static final String TENANT = "recUS3";
    private static final String FINGERPRINT = "0".repeat(64);

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StaleIngestionRecovery recovery;

    @Test
    void recoverMarksStaleIngestingDocumentsAndUnfinishedRunsFailed() {
        cleanup();
        long tenantId = insertTenant();
        long pipelineId = insertPipeline(tenantId);

        long staleDoc = insertDocument(tenantId, "stale-doc", sixMinutesAgo());
        long freshDoc = insertDocument(tenantId, "fresh-doc", Timestamp.valueOf(LocalDateTime.now()));
        long staleNoRunDoc = insertDocument(tenantId, "stale-norun-doc", sixMinutesAgo());
        // 模拟进程重启残留：stale-doc 有一条已写入状态但未完结（finished_at 为空）的 run
        insertRun(staleDoc, pipelineId, "SUCCESS", null);
        // 已完结的 run 不应被恢复任务误改
        insertRun(staleDoc, pipelineId, "SUCCESS", Timestamp.valueOf(LocalDateTime.now()));

        recovery.recover();

        // 滞留文档 FAILED；未滞留文档保持 INGESTING
        assertThat(documentStatus(staleDoc)).isEqualTo("FAILED");
        assertThat(documentStatus(staleNoRunDoc)).isEqualTo("FAILED");
        assertThat(documentStatus(freshDoc)).isEqualTo("INGESTING");

        // 未完结 run 同步 FAILED("interrupted") 且补记 finished_at；已完结 run 不受影响
        List<Map<String, Object>> runs = jdbcTemplate.queryForList(
                "SELECT status, error_message, finished_at FROM ingestion_run WHERE document_id = ? ORDER BY id",
                staleDoc);
        assertThat(runs).hasSize(2);
        Map<String, Object> unfinished = runs.get(0);
        assertThat(unfinished.get("status")).isEqualTo("FAILED");
        assertThat(unfinished.get("error_message")).isEqualTo("interrupted");
        assertThat(unfinished.get("finished_at")).isNotNull();
        Map<String, Object> finished = runs.get(1);
        assertThat(finished.get("status")).isEqualTo("SUCCESS");
        assertThat(finished.get("error_message")).isNull();

        // 再跑一次：无滞留数据，状态稳定（幂等）
        recovery.recover();
        assertThat(documentStatus(staleDoc)).isEqualTo("FAILED");
        assertThat(documentStatus(freshDoc)).isEqualTo("INGESTING");
    }

    private void cleanup() {
        Long tenantId = jdbcTemplate.query(
                "SELECT id FROM tenant WHERE tenant_code = ?", rs -> rs.next() ? rs.getLong(1) : null, TENANT);
        if (tenantId != null) {
            jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id IN (SELECT id FROM document WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM ingestion_run WHERE document_id IN (SELECT id FROM document WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM document WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM ingestion_pipeline WHERE tenant_id = ?", tenantId);
        }
    }

    private long insertTenant() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO tenant (tenant_code, name) VALUES (?, ?)
                ON CONFLICT (tenant_code) DO UPDATE SET name = EXCLUDED.name,
                    enabled = TRUE, deleted = FALSE
                RETURNING id""",
                Long.class, TENANT, TENANT);
    }

    private long insertPipeline(long tenantId) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO ingestion_pipeline
                    (tenant_id, pipeline_key, strategy, max_segment_tokens, overlap_tokens,
                     embedding_store_key, embedding_model_key, enabled)
                VALUES (?, 'main', 'RECURSIVE', 500, 50, 'default', 'default', TRUE)
                RETURNING id""",
                Long.class, tenantId);
    }

    private long insertDocument(long tenantId, String docName, Timestamp updatedAt) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO document (tenant_id, doc_name, content_fingerprint, status, created_at, updated_at)
                VALUES (?, ?, ?, 'INGESTING', ?, ?)
                RETURNING id""",
                Long.class, tenantId, docName, FINGERPRINT, updatedAt, updatedAt);
    }

    private void insertRun(long documentId, long pipelineId, String status, Timestamp finishedAt) {
        jdbcTemplate.update(
                "INSERT INTO ingestion_run (document_id, pipeline_id, status, chunk_count, started_at, finished_at) "
                        + "VALUES (?, ?, ?, 0, ?, ?)",
                documentId, pipelineId, status, sixMinutesAgo(), finishedAt);
    }

    private Timestamp sixMinutesAgo() {
        return Timestamp.valueOf(LocalDateTime.now().minusMinutes(6));
    }

    private String documentStatus(long documentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, documentId);
    }
}

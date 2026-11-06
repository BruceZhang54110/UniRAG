package com.unirag.ingestion;

import com.unirag.infrastructure.persistence.service.DocumentIngestionJobService;
import com.unirag.ingestion.support.BinaryTestDocuments;
import com.unirag.ingestion.support.DocumentTextExtractor;
import org.apache.opendal.Operator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 022 US1 验收（spec.md US1 场景 4，FR-003/FR-011）：异步 worker 路径对 PDF 按版本对象
 * 的 fileName 抽取入库，任务 READY 且 extracted_chars 与抽取文本长度一致。
 * 租户码全局唯一：{@code pdfWk}。
 */
class PdfExtractionWorkerIT extends AbstractIngestionIT {

    private static final String TENANT = "pdfWk";
    private static final String DOC = "pdf-worker-doc";
    private static final String TABLE = "embeddings_pdfwk";
    private static final String FILE_NAME = "manual.pdf";

    @Autowired JdbcTemplate jdbc;
    @Autowired Operator documentStorageOperator;
    @Autowired DocumentIngestionJobService jobs;
    @Autowired DocumentIngestionWorker worker;
    @Autowired DocumentTextExtractor textExtractor;

    private long tenantId;
    private long documentId;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "default", TABLE, 1024);
        insertPipeline(tenantId, "main", 500, 50, "default", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("default", storeBean(TENANT, "default")));
        documentId = jdbc.queryForObject("""
                INSERT INTO document (tenant_id, doc_name, title, content_fingerprint, status, chunk_count, version, deleted)
                VALUES (?, ?, ?, ?, 'INGESTING', 0, 1, FALSE) RETURNING id""",
                Long.class, tenantId, DOC, DOC, fingerprint("pdf version one"));
    }

    @Test
    void pdfJobExtractsTextAndRecordsExtractedChars() throws Exception {
        byte[] pdf = BinaryTestDocuments.pdfWithText(
                "General Requirements",
                "Stick to the basic principles and keep the scope unchanged.");
        String expectedText = textExtractor.extract(FILE_NAME, pdf);

        long versionId = enqueueVersion(1, FILE_NAME, "application/pdf", pdf);
        assertThat(worker.runBatch()).isEqualTo(1);

        Long jobId = jdbc.queryForObject(
                "SELECT id FROM document_ingestion_job WHERE document_version_id = ?", Long.class, versionId);
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, extracted_chars FROM document_ingestion_job WHERE id = ?", jobId);
        assertThat(job.get("status")).isEqualTo("READY");
        assertThat(((Number) job.get("extracted_chars")).longValue()).isEqualTo(expectedText.length());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_version_id = ? AND content LIKE ?",
                Long.class, versionId, "%General Requirements%")).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + TABLE + " WHERE metadata->>'document_version_id'=?::text",
                Long.class, String.valueOf(versionId))).isPositive();
    }

    private long enqueueVersion(int version, String fileName, String mimeType, byte[] blob) {
        String fp = fingerprint(blob);
        String path = TENANT + "/" + DOC + "/v" + version;
        long versionId = jdbc.queryForObject("""
                INSERT INTO document_ingestion_version
                    (tenant_id, document_id, version, content_fingerprint, storage_path, file_name, mime_type, size_bytes, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE) RETURNING id""",
                Long.class, tenantId, documentId, version, fp, path, fileName, mimeType, blob.length);
        writeWithRetry(path, blob);
        jdbc.update("""
                INSERT INTO document_ingestion_job
                    (tenant_id, document_id, document_version_id, document_version, content_fingerprint,
                     status, stage, attempt_count, max_attempts, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 'CLAIMED', 0, 3, CURRENT_TIMESTAMP)""",
                tenantId, documentId, versionId, version, fp);
        return versionId;
    }

    private void writeWithRetry(String path, byte[] bytes) {
        for (int attempt = 1; ; attempt++) {
            try {
                documentStorageOperator.write(path, bytes);
                return;
            } catch (RuntimeException e) {
                if (attempt >= 6) {
                    throw e;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private String fingerprint(byte[] content) {
        return com.unirag.ingestion.support.DocumentFingerprint.sha256(content);
    }

    private String fingerprint(String content) {
        return com.unirag.ingestion.support.DocumentFingerprint.sha256(content);
    }
}

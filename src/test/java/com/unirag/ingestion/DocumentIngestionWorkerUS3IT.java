package com.unirag.ingestion;

import com.unirag.infrastructure.mp.entity.DocumentIngestionJob;
import com.unirag.infrastructure.mp.service.DocumentIngestionJobService;
import org.apache.opendal.Operator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentIngestionWorkerUS3IT extends AbstractIngestionIT {
    private static final String TENANT = "workerUS3";
    private static final String DOC = "versioned-worker-doc";
    private static final String TABLE = "embeddings_workerus3";

    @Autowired JdbcTemplate jdbc;
    @Autowired Operator documentStorageOperator;
    @Autowired DocumentIngestionJobService jobs;
    @Autowired DocumentIngestionJobClaimRepository claims;
    @Autowired DocumentIngestionWorker worker;

    private long tenantId;
    private long documentId;
    private long pipelineId;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "default", TABLE, 1024);
        insertPipeline(tenantId, "main", 500, 50, "default", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("default", storeBean(TENANT, "default")));
        pipelineId = jdbc.queryForObject(
                "SELECT id FROM ingestion_pipeline WHERE tenant_id=? AND pipeline_key='main'", Long.class, tenantId);
        documentId = jdbc.queryForObject("""
                INSERT INTO document (tenant_id, doc_name, title, content_fingerprint, status, chunk_count, version, deleted)
                VALUES (?, ?, ?, ?, 'INGESTING', 0, 1, FALSE) RETURNING id""",
                Long.class, tenantId, DOC, DOC, fingerprint("version one"));
    }

    @Test
    void newVersionDoesNotHideActiveVersionUntilReady() {
        long v1 = enqueueVersion(1, "version one", true);
        runWorkerOnce();
        assertThat(activeVersion()).isEqualTo(v1);
        long oldChunks = count("SELECT count(*) FROM document_chunk WHERE document_version_id=?", v1);
        long oldRuns = count("SELECT count(*) FROM ingestion_run WHERE document_version_id=?", v1);
        long oldVectors = count("SELECT count(*) FROM " + TABLE + " WHERE metadata->>'document_version_id'=?::text", v1);
        assertThat(oldChunks).isPositive();
        assertThat(oldRuns).isEqualTo(1);
        assertThat(oldVectors).isEqualTo(oldChunks);

        long v2 = enqueueVersion(2, "version two", true);
        DocumentIngestionJob claimed = claims.claim(1, 3, 300_000).getFirst();
        assertThat(claimed.getDocumentVersionId()).isEqualTo(v2);
        assertThat(activeVersion()).isEqualTo(v1);
        assertThat(count("SELECT count(*) FROM document_chunk WHERE document_version_id=?", v1)).isEqualTo(oldChunks);
        assertThat(count("SELECT count(*) FROM ingestion_run WHERE document_version_id=?", v1)).isEqualTo(oldRuns);
        assertThat(count("SELECT count(*) FROM " + TABLE + " WHERE metadata->>'document_version_id'=?::text", v1)).isEqualTo(oldVectors);
        assertThat(jobs.markRetryOrFailed(claimed.getId(), claimed.getLeaseToken(), claimed.getAttemptCount(),
                claimed.getMaxAttempts(), "TEST_FAILURE", "temporary", Duration.ZERO)).isTrue();
        assertThat(activeVersion()).isEqualTo(v1);

        runWorkerOnce();
        assertThat(activeVersion()).isEqualTo(v2);
        assertThat(count("SELECT count(*) FROM document_chunk WHERE document_version_id=?", v1)).isEqualTo(oldChunks);
        assertThat(count("SELECT count(*) FROM ingestion_run WHERE document_version_id=?", v1)).isEqualTo(oldRuns);
        assertThat(count("SELECT count(*) FROM " + TABLE + " WHERE metadata->>'document_version_id'=?::text", v2)).isPositive();
        assertThat(count("SELECT count(*) FROM document_ingestion_job WHERE id=? AND status='READY'", jobForVersion(2))).isEqualTo(1);
    }

    @Test
    void repeatedWorkerExecutionDoesNotDuplicateRunsOrChunks() {
        enqueueVersion(1, "version one", true);
        assertThat(worker.runBatch()).isEqualTo(1);
        long chunks = count("SELECT count(*) FROM document_chunk WHERE document_id=?", documentId);
        long runs = count("SELECT count(*) FROM ingestion_run WHERE document_id=?", documentId);
        long job = jobForVersion(1);
        jdbc.update("UPDATE document_ingestion_job SET status='QUEUED', lease_token=NULL, leased_until=NULL, next_attempt_at=CURRENT_TIMESTAMP WHERE id=?", job);
        assertThat(worker.runBatch()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document_chunk WHERE document_id=?", documentId)).isEqualTo(chunks);
        assertThat(count("SELECT count(*) FROM ingestion_run WHERE document_id=?", documentId)).isEqualTo(runs);
        assertThat(count("SELECT count(*) FROM document_ingestion_job WHERE id=? AND status='READY'", job)).isEqualTo(1);
    }

    @Test
    void retryPolicyRecoversAndExhaustsAndStaleTokenCannotFinalize() {
        long job = enqueueVersion(1, "version one", true);
        DocumentIngestionJob first = claims.claim(1, 3, 300_000).getFirst();
        assertThat(jobs.markRetryOrFailed(job, first.getLeaseToken(), first.getAttemptCount(), 3,
                "TEST_FAILURE", "temporary", Duration.ZERO)).isTrue();
        DocumentIngestionJob second = claims.claim(1, 3, 300_000).getFirst();
        assertThat(second.getAttemptCount()).isEqualTo(2);
        assertThat(jobs.markRetryOrFailed(job, second.getLeaseToken(), second.getAttemptCount(), 3,
                "TEST_FAILURE", "temporary", Duration.ZERO)).isTrue();
        DocumentIngestionJob third = claims.claim(1, 3, 300_000).getFirst();
        assertThat(jobs.markRetryOrFailed(job, third.getLeaseToken(), third.getAttemptCount(), 3,
                "TEST_FAILURE", "temporary", Duration.ZERO)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM document_ingestion_job WHERE id=?", String.class, job)).isEqualTo("FAILED");
        assertThat(jobs.markReady(job, first.getLeaseToken(), tenantId, first.getDocumentVersionId())).isFalse();
    }

    private void runWorkerOnce() {
        assertThat(worker.runBatch()).isEqualTo(1);
    }

    private long enqueueVersion(int version, String content, boolean writeBlob) {
        String fp = fingerprint(content);
        String path = TENANT + "/" + DOC + "/v" + version;
        long versionId = jdbc.queryForObject("""
                INSERT INTO document_ingestion_version
                    (tenant_id, document_id, version, content_fingerprint, storage_path, file_name, mime_type, size_bytes, active)
                VALUES (?, ?, ?, ?, ?, 'document.txt', 'text/plain', ?, FALSE) RETURNING id""",
                Long.class, tenantId, documentId, version, fp, path, content.length());
        if (writeBlob) {
            documentStorageOperator.write(path, content.getBytes(StandardCharsets.UTF_8));
        }
        jdbc.update("""
                INSERT INTO document_ingestion_job
                    (tenant_id, document_id, document_version_id, document_version, content_fingerprint,
                     status, stage, attempt_count, max_attempts, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 'UPLOADED', 0, 3, CURRENT_TIMESTAMP)""",
                tenantId, documentId, versionId, version, fp);
        return versionId;
    }

    private long jobForVersion(int version) {
        return jdbc.queryForObject("SELECT id FROM document_ingestion_job WHERE document_id=? AND document_version=?", Long.class, documentId, version);
    }

    private long activeVersion() {
        return jdbc.queryForObject("SELECT id FROM document_ingestion_version WHERE document_id=? AND active=TRUE", Long.class, documentId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private String fingerprint(String content) {
        return com.unirag.ingestion.support.DocumentFingerprint.sha256(content);
    }
}

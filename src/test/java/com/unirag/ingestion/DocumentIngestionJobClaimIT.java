package com.unirag.ingestion;

import com.unirag.AbstractPostgresIntegrationTest;
import com.unirag.infrastructure.persistence.entity.DocumentIngestionJob;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentIngestionJobClaimIT extends AbstractPostgresIntegrationTest {
    private static final String TENANT = "asyncClaimIT";
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentIngestionJobClaimRepository claims;

    @AfterAll
    static void cleanup(@Autowired JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM ingestion_run WHERE document_id IN (SELECT id FROM document WHERE tenant_id=(SELECT id FROM tenant WHERE tenant_code=?))", TENANT);
        jdbc.update("DELETE FROM document_chunk WHERE document_id IN (SELECT id FROM document WHERE tenant_id=(SELECT id FROM tenant WHERE tenant_code=?))", TENANT);
        jdbc.update("DELETE FROM document_ingestion_job WHERE tenant_id=(SELECT id FROM tenant WHERE tenant_code=?)", TENANT);
        jdbc.update("DELETE FROM document_ingestion_version WHERE tenant_id=(SELECT id FROM tenant WHERE tenant_code=?)", TENANT);
        jdbc.update("DELETE FROM document WHERE tenant_id=(SELECT id FROM tenant WHERE tenant_code=?)", TENANT);
    }

    @Test
    void concurrentClaimsAreExclusiveAndDifferentRowsCanBeClaimed() throws Exception {
        long tenant = tenant();
        long doc = document(tenant);
        enqueue(tenant, doc, 1);
        enqueue(tenant, doc, 2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> claimAfter(start, ready));
            var b = pool.submit(() -> claimAfter(start, ready));
            ready.await();
            start.countDown();
            List<Long> idsA = a.get().stream().map(DocumentIngestionJob::getId).toList();
            List<Long> idsB = b.get().stream().map(DocumentIngestionJob::getId).toList();
            assertEquals(2, idsA.size() + idsB.size());
            assertTrue(java.util.Collections.disjoint(idsA, idsB));
        }
    }

    @Test
    void expiredProcessingLeaseIsReclaimedWithoutConsumingAnotherAttempt() {
        long tenant = tenant();
        long doc = document(tenant);
        long job = enqueue(tenant, doc, 3);
        jdbc.update("UPDATE document_ingestion_job SET status='PROCESSING', attempt_count=1, lease_token='old', leased_until=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?", job);
        DocumentIngestionJob reclaimed = claims.claim(1, 3, 60_000).getFirst();
        assertEquals(job, reclaimed.getId());
        assertEquals(1, reclaimed.getAttemptCount());
        assertNotEquals("old", reclaimed.getLeaseToken());
    }

    private List<DocumentIngestionJob> claimAfter(CountDownLatch start, CountDownLatch ready) {
        ready.countDown();
        try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return claims.claim(1, 3, 60_000);
    }

    private long tenant() {
        return jdbc.queryForObject("INSERT INTO tenant (tenant_code,name) VALUES (?,?) ON CONFLICT (tenant_code) DO UPDATE SET name=EXCLUDED.name, enabled=TRUE, deleted=FALSE RETURNING id", Long.class, TENANT, TENANT);
    }

    private long document(long tenant) {
        String name = TENANT + System.nanoTime();
        return jdbc.queryForObject("INSERT INTO document (tenant_id,doc_name,title,content_fingerprint,status,chunk_count,version,deleted) VALUES (?,?,'claim test',repeat('a',64),'INGESTING',0,1,FALSE) RETURNING id", Long.class, tenant, name);
    }

    private long enqueue(long tenant, long document, int versionNo) {
        long versionId = jdbc.queryForObject("INSERT INTO document_ingestion_version (tenant_id,document_id,version,content_fingerprint,storage_path,file_name,mime_type,size_bytes) VALUES (?,?,?,repeat('b',64),'claim/test','test.txt','text/plain',1) RETURNING id", Long.class, tenant, document, versionNo);
        return jdbc.queryForObject("INSERT INTO document_ingestion_job (tenant_id,document_id,document_version_id,document_version,content_fingerprint,status,stage,attempt_count,max_attempts,next_attempt_at) VALUES (?,?,?,?,repeat('b',64),'QUEUED','UPLOADED',0,3,CURRENT_TIMESTAMP) RETURNING id", Long.class, tenant, document, versionId, versionNo);
    }
}

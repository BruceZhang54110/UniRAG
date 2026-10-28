package com.unirag.ingestion;

import com.unirag.infrastructure.persistence.entity.DocumentIngestionJob;
import com.unirag.infrastructure.persistence.entity.DocumentIngestionVersion;
import com.unirag.infrastructure.persistence.entity.Tenant;
import com.unirag.infrastructure.persistence.mapper.TenantMapper;
import com.unirag.infrastructure.persistence.service.DocumentIngestionJobService;
import lombok.extern.slf4j.Slf4j;
import org.apache.opendal.Operator;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
public class DocumentIngestionWorker {
    private final DocumentIngestionJobClaimRepository claims;
    private final DocumentIngestionJobService jobs;
    private final IngestionService ingestion;
    private final Operator storage;
    private final TenantMapper tenants;
    private final AsyncIngestionProperties properties;
    private final DocumentIngestionRetryPolicy retryPolicy;

    public DocumentIngestionWorker(DocumentIngestionJobClaimRepository claims, DocumentIngestionJobService jobs,
                                   IngestionService ingestion, Operator documentStorageOperator, TenantMapper tenants,
                                   AsyncIngestionProperties properties, DocumentIngestionRetryPolicy retryPolicy) {
        this.claims = claims;
        this.jobs = jobs;
        this.ingestion = ingestion;
        this.storage = documentStorageOperator;
        this.tenants = tenants;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
    }

    public int runBatch() {
        List<DocumentIngestionJob> claimed = claims.claim(properties.getBatchSize(), properties.getMaxAttempts(),
                properties.getLease().toMillis());
        claimed.forEach(this::process);
        return claimed.size();
    }

    private void process(DocumentIngestionJob job) {
        String token = job.getLeaseToken();
        try {
            DocumentIngestionVersion version = jobs.findVersion(job.getTenantId(), job.getDocumentId(), job.getDocumentVersion());
            if (version == null || !version.getId().equals(job.getDocumentVersionId())
                    || !version.getContentFingerprint().equals(job.getContentFingerprint())) {
                throw new JobFailure("SOURCE_UNAVAILABLE");
            }
            Tenant tenant = tenants.findEnabledById(job.getTenantId());
            String tenantCode = tenant == null ? null : tenant.getTenant_code();
            if (tenantCode == null) throw new JobFailure("TENANT_UNAVAILABLE");
            String docName = jobs.findDocumentName(job.getTenantId(), job.getDocumentId());
            if (docName == null || !jobs.markIngesting(job.getId(), token)) return;
            byte[] bytes = storage.read(version.getStoragePath());
            String text = new String(bytes, StandardCharsets.UTF_8);
            IngestOutcome outcome = ingestion.ingest(new IngestRequest(tenantCode, docName, version.getTitle(), text,
                    "async-job", version.getId(), job.getId(), job.getContentFingerprint()));
            if (outcome.status() != IngestOutcome.Status.INGESTED) {
                throw new JobFailure(outcome.runs().isEmpty() ? "NO_ENABLED_PIPELINE" : "INGESTION_FAILED");
            }
            jobs.markReady(job.getId(), token, job.getTenantId(), version.getId());
        } catch (Exception failure) {
            String code = failure instanceof JobFailure jobFailure ? jobFailure.code : retryPolicy.errorCode(failure);
            String message = retryPolicy.safeMessage(code);
            jobs.markRetryOrFailed(job.getId(), token, job.getAttemptCount(), job.getMaxAttempts(), code, message,
                    retryPolicy.delayAfter(job.getAttemptCount()));
            log.warn("Ingestion job attempt failed: jobId={}, tenantId={}, code={}", job.getId(), job.getTenantId(), code);
        }
    }

    private static final class JobFailure extends RuntimeException {
        private final String code;
        private JobFailure(String code) { this.code = code; }
    }
}

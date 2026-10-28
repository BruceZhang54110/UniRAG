package com.unirag.infrastructure.persistence.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unirag.infrastructure.persistence.entity.Document;
import com.unirag.infrastructure.persistence.entity.DocumentIngestionJob;
import com.unirag.infrastructure.persistence.entity.DocumentIngestionVersion;
import com.unirag.infrastructure.persistence.mapper.DocumentIngestionJobMapper;
import com.unirag.infrastructure.persistence.mapper.DocumentIngestionVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Service
public class DocumentIngestionJobService {

    private final DocumentIngestionJobMapper jobMapper;
    private final DocumentIngestionVersionMapper versionMapper;

    public DocumentIngestionJobService(DocumentIngestionJobMapper jobMapper,
                                       DocumentIngestionVersionMapper versionMapper) {
        this.jobMapper = jobMapper;
        this.versionMapper = versionMapper;
    }

    public void lockTenant(Long tenantId) {
        jobMapper.lockTenant(tenantId);
    }

    @Transactional
    public DocumentIngestionJob createOrReuse(Document document, String fingerprint,
                                               String storagePath, String title, String fileName,
                                               String mimeType, long sizeBytes, int maxAttempts) {
        DocumentIngestionJob existing = findByTenantDocumentAndFingerprint(
                document.getTenantId(), document.getId(), fingerprint);
        if (existing != null) {
            return existing;
        }

        Integer nextVersion = jobMapper.nextVersion(document.getTenantId(), document.getId());
        Date now = new Date();
        DocumentIngestionVersion version = new DocumentIngestionVersion();
        version.setTenantId(document.getTenantId());
        version.setDocumentId(document.getId());
        version.setVersion(nextVersion);
        version.setContentFingerprint(fingerprint);
        version.setStoragePath(storagePath);
        version.setTitle(title);
        version.setFileName(fileName);
        version.setMimeType(mimeType);
        version.setSizeBytes(sizeBytes);
        version.setActive(false);
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        int insertedVersion = jobMapper.insertVersionSnapshot(version);
        if (insertedVersion == 0) {
            DocumentIngestionJob reused = findByTenantDocumentAndFingerprint(
                    document.getTenantId(), document.getId(), fingerprint);
            if (reused != null) {
                return reused;
            }
            throw new IllegalStateException("Document ingestion version conflict without reusable job");
        }
        version.setId(versionMapper.selectOne(new LambdaQueryWrapper<DocumentIngestionVersion>()
                .eq(DocumentIngestionVersion::getTenantId, document.getTenantId())
                .eq(DocumentIngestionVersion::getDocumentId, document.getId())
                .eq(DocumentIngestionVersion::getVersion, nextVersion)).getId());

        DocumentIngestionJob job = new DocumentIngestionJob();
        job.setTenantId(document.getTenantId());
        job.setDocumentId(document.getId());
        job.setDocumentVersionId(version.getId());
        job.setDocumentVersion(nextVersion);
        job.setContentFingerprint(fingerprint);
        job.setStatus("QUEUED");
        job.setStage("UPLOADED");
        job.setAttemptCount(0);
        job.setMaxAttempts(maxAttempts);
        job.setNextAttemptAt(now);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        int insertedJob = jobMapper.insertJob(job);
        if (insertedJob == 0) {
            return findByTenantDocumentVersionAndFingerprint(
                    document.getTenantId(), document.getId(), nextVersion, fingerprint);
        }
        return findByTenantDocumentVersionAndFingerprint(
                document.getTenantId(), document.getId(), nextVersion, fingerprint);
    }

    public DocumentIngestionJob findByTenantDocumentVersionAndFingerprint(Long tenantId,
                                                                           Long documentId,
                                                                           Integer version,
                                                                           String fingerprint) {
        return jobMapper.selectOne(new LambdaQueryWrapper<DocumentIngestionJob>()
                .eq(DocumentIngestionJob::getTenantId, tenantId)
                .eq(DocumentIngestionJob::getDocumentId, documentId)
                .eq(DocumentIngestionJob::getDocumentVersion, version)
                .eq(DocumentIngestionJob::getContentFingerprint, fingerprint));
    }

    public DocumentIngestionJob findByTenantDocumentAndFingerprint(Long tenantId, Long documentId, String fingerprint) {
        return jobMapper.selectOne(new LambdaQueryWrapper<DocumentIngestionJob>()
                .eq(DocumentIngestionJob::getTenantId, tenantId)
                .eq(DocumentIngestionJob::getDocumentId, documentId)
                .eq(DocumentIngestionJob::getContentFingerprint, fingerprint)
                .orderByDesc(DocumentIngestionJob::getDocumentVersion)
                .last("LIMIT 1"));
    }

    public DocumentIngestionJob findByTenantDocumentAndJob(Long tenantId, Long documentId, Long jobId) {
        return jobMapper.selectOne(new LambdaQueryWrapper<DocumentIngestionJob>()
                .eq(DocumentIngestionJob::getTenantId, tenantId)
                .eq(DocumentIngestionJob::getDocumentId, documentId)
                .eq(DocumentIngestionJob::getId, jobId));
    }

    public String findDocumentName(Long tenantId, Long documentId) {
        return jobMapper.findDocumentName(tenantId, documentId);
    }

    public DocumentIngestionVersion findVersion(Long tenantId, Long documentId, Integer version) {
        return versionMapper.selectOne(new LambdaQueryWrapper<DocumentIngestionVersion>()
                .eq(DocumentIngestionVersion::getTenantId, tenantId)
                .eq(DocumentIngestionVersion::getDocumentId, documentId)
                .eq(DocumentIngestionVersion::getVersion, version));
    }

    public DocumentIngestionJob findById(Long tenantId, Long jobId) {
        return jobMapper.selectOne(new LambdaQueryWrapper<DocumentIngestionJob>()
                .eq(DocumentIngestionJob::getTenantId, tenantId).eq(DocumentIngestionJob::getId, jobId));
    }

    public boolean markIngesting(Long id, String token) {
        return jobMapper.markIngesting(id, token) == 1;
    }

    @Transactional
    public boolean markReady(Long id, String token, Long tenantId, Long versionId) {
        DocumentIngestionJob current = jobMapper.lockForReady(id, tenantId, token, versionId);
        if (current == null) return false;

        int readyChunks = jobMapper.countVersionChunks(current.getDocumentId(), versionId);
        if (readyChunks == 0) return false;
        jobMapper.deactivateActiveVersions(tenantId, current.getDocumentId(), versionId);
        int activated = jobMapper.activateVersion(tenantId, current.getDocumentId(), versionId);
        if (activated != 1) throw new IllegalStateException("Ingestion version disappeared during finalization");
        int finalized = jobMapper.finalizeReady(id, token, versionId);
        if (finalized != 1) throw new IllegalStateException("Ingestion job lease changed during finalization");
        jobMapper.updateDocumentReady(tenantId, current.getDocumentId(), versionId);
        return true;
    }

    public boolean markRetryOrFailed(Long id, String token, int attempt, int maxAttempts,
                                    String code, String message, java.time.Duration delay) {
        return jobMapper.markRetryOrFailed(id, token, attempt, maxAttempts, code, message, delay.toMillis()) == 1;
    }

    public int recoverExpiredLeases() {
        return jobMapper.recoverExpiredLeases();
    }
}

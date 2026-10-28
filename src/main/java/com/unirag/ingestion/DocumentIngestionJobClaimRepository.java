package com.unirag.ingestion;

import com.unirag.infrastructure.persistence.entity.DocumentIngestionJob;
import com.unirag.infrastructure.persistence.mapper.DocumentIngestionJobMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class DocumentIngestionJobClaimRepository {
    private final DocumentIngestionJobMapper mapper;

    public DocumentIngestionJobClaimRepository(DocumentIngestionJobMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public List<DocumentIngestionJob> claim(int limit, int maxAttempts, long leaseMillis) {
        if (limit < 1 || leaseMillis < 1) return List.of();
        return mapper.claimBatch(limit, leaseMillis);
    }
}

package com.unirag.upload.dto;

import java.util.Date;

public record DocumentIngestionJobStatusResponse(Long jobId, Long documentId, String documentName,
                                                 Integer documentVersion, String status, String stage,
                                                 Integer attemptCount, Integer maxAttempts,
                                                 Date nextAttemptAt, Date createdAt, Date updatedAt,
                                                 Date finishedAt, Error error) {
    public record Error(String code, String message) {
    }
}

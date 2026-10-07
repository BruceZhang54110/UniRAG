package com.unirag.upload.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

public record AsyncDocumentUploadResponse(Long documentId, String documentName, Integer documentVersion,
                                          Long jobId, String status, String stage,
                                          @JsonFormat(shape = JsonFormat.Shape.STRING)
                                          Date createdAt, String statusUrl) {
}

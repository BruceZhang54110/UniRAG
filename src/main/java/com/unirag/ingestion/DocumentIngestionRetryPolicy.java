package com.unirag.ingestion;

import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class DocumentIngestionRetryPolicy {
    private final AsyncIngestionProperties properties;

    public DocumentIngestionRetryPolicy(AsyncIngestionProperties properties) {
        this.properties = properties;
    }

    public Duration delayAfter(int failedAttempt) {
        long base = Math.max(1, properties.getBackoffBase().toMillis());
        long cap = Math.max(base, properties.getBackoffMax().toMillis());
        int shift = Math.min(30, Math.max(0, failedAttempt - 1));
        long multiplier = 1L << shift;
        long millis = base > cap / multiplier ? cap : Math.min(cap, base * multiplier);
        return Duration.ofMillis(millis);
    }

    public String errorCode(Throwable failure) {
        if (failure instanceof IllegalStateException && "NO_ENABLED_PIPELINE".equals(failure.getMessage())) {
            return "NO_ENABLED_PIPELINE";
        }
        return "INGESTION_FAILED";
    }

    public String safeMessage(String code) {
        return switch (code) {
            case "NO_ENABLED_PIPELINE" -> "No enabled ingestion pipeline is available.";
            case "SOURCE_UNAVAILABLE" -> "The stored source could not be read.";
            case "TENANT_UNAVAILABLE" -> "The tenant for this job is unavailable.";
            default -> "Document ingestion failed; it may be retried.";
        };
    }
}

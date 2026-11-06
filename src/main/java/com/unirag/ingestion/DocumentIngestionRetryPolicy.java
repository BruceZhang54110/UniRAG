package com.unirag.ingestion;

import com.unirag.ingestion.support.TextExtractionException;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 任务重试策略与错误码安全文案。
 *
 * <p>失败原因归类（022 data-model）：确定性解析失败 {@code PARSE_FAILED} / {@code EMPTY_EXTRACTED_TEXT}
 * 由 {@link TextExtractionException} 携带，worker 直接终态、不占用重试预算（clarify Q1）；
 * 其余码（INGESTION_FAILED / SOURCE_UNAVAILABLE / TENANT_UNAVAILABLE / NO_ENABLED_PIPELINE）为
 * 瞬时性，按重试预算退避重试。
 */
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
        if (failure instanceof TextExtractionException extraction) {
            return extraction.getCode();
        }
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
            case "PARSE_FAILED" -> "文件损坏或格式不受支持，无法提取文字";
            case "EMPTY_EXTRACTED_TEXT" -> "未能从文件中提取到文字内容，可能是扫描件或纯图片文档";
            default -> "Document ingestion failed; it may be retried.";
        };
    }
}

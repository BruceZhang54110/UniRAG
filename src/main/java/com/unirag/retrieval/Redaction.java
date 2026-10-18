package com.unirag.retrieval;

import java.util.regex.Pattern;

/**
 * 敏感信息脱敏（FR-008，宪法原则 V）：错误原因进入观测记录或错误响应之前统一从此处经过。
 *
 * <p>覆盖 {@code api-key}、{@code password}、{@code token} 等"键=值"与 {@code Bearer <token>} 形态；
 * 排障所需的异常类型与定位信息（租户、资源类型、资源 key）保留。
 */
public final class Redaction {

    /** 单条原因/摘要的留存上限，与 005 {@code ingestion_run} 的既有约定一致。 */
    public static final int MAX_LENGTH = 500;

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(api[-_ ]?key|password|passwd|secret|token|authorization|bearer)(\\s*[=:]\\s*|\\s+)\\S+");

    private static final String SECRET_MASK = "$1=***";

    private Redaction() {
    }

    /** 先脱敏再截断：避免截断把密钥切成"半截"而绕过脱敏规则。 */
    public static String sanitize(String value, int maxLength) {
        if (value == null || value.isEmpty() || maxLength <= 0) {
            return null;
        }
        String redacted = SECRET_PATTERN.matcher(value).replaceAll(SECRET_MASK);
        return redacted.length() > maxLength ? redacted.substring(0, maxLength) : redacted;
    }

    /** 异常根因的"类型 + 已脱敏 message"，可直接进入日志、观测记录与错误响应。 */
    public static String describe(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String text = message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
        return sanitize(text, MAX_LENGTH);
    }
}

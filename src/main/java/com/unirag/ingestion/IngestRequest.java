package com.unirag.ingestion;

/**
 * 摄取请求（contracts §1）。
 *
 * @param tenantCode 租户码（必填）
 * @param docName    业务文档标识，租户内唯一（必填）
 * @param title      可选展示名
 * @param content    文档内容（必填，非空白；本期为纯文本/Markdown）
 * @param source     可选来源标识
 */
public record IngestRequest(String tenantCode, String docName, String title, String content, String source,
                            Long versionId, Long jobId, String expectedFingerprint) {

    public IngestRequest(String tenantCode, String docName, String title, String content, String source) {
        this(tenantCode, docName, title, content, source, null, null, null);
    }

    public IngestRequest(String tenantCode, String docName, String title, String content) {
        this(tenantCode, docName, title, content, null, null, null, null);
    }

    public IngestRequest(String tenantCode, String docName, String content) {
        this(tenantCode, docName, null, content, null, null, null, null);
    }
}

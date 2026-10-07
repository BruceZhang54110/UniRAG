package com.unirag.upload.dto;

/**
 * 文档列表项（contracts §4）：document JOIN document_file 的查询投影。
 *
 * @param docName       文档标识（租户内唯一）
 * @param title         展示名（可空）
 * @param status        INGESTING / INGESTED / PARTIAL / FAILED
 * @param version       文档版本
 * @param chunkCount    切片总数
 * @param fileSizeBytes 原文字节数（无原文元数据时为 null，如 005 直摄取的文档）
 * @param mimeType      登记 mime（同上可空）
 * @param createdAt     创建时间（epoch millis）
 */
public record DocumentInfoItem(String docName, String title, String status, int version,
                               long chunkCount, Long fileSizeBytes, String mimeType,
                               long createdAt) {
}

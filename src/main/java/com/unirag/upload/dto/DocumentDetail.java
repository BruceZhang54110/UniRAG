package com.unirag.upload.dto;

/**
 * 文档详情（contracts §4）：列表字段 + updatedAt、source。
 *
 * @param updatedAt 更新时间（epoch millis）
 * @param source    来源（007 上传通道固定 "upload"；document 表无 source 列，直摄取语义见 §7 约定）
 */
public record DocumentDetail(String docName, String title, String status, int version,
                             long chunkCount, Long fileSizeBytes, String mimeType,
                             long createdAt, long updatedAt, String source) {

    public static DocumentDetail of(DocumentInfoItem item, long updatedAt) {
        return new DocumentDetail(item.docName(), item.title(), item.status(), item.version(),
                item.chunkCount(), item.fileSizeBytes(), item.mimeType(),
                item.createdAt(), updatedAt, "upload");
    }
}

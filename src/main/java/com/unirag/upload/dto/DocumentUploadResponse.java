package com.unirag.upload.dto;

import java.util.List;

/**
 * 上传响应（contracts §2）：文档结果 + 逐管线摄取 outcome。
 *
 * @param docName        文档标识（租户内唯一）
 * @param title          展示名（可空）
 * @param status         INGESTED / NOOP / PARTIAL / FAILED（NOOP = 同内容重复上传）
 * @param version        文档版本（替换 +1；NOOP 不变）
 * @param chunkCount     全部管线片段总数
 * @param fileSizeBytes  原文字节数
 * @param pipelines      每启用管线一条执行结果
 */
public record DocumentUploadResponse(String docName, String title, String status, int version,
                                     int chunkCount, long fileSizeBytes,
                                     List<PipelineOutcome> pipelines) {

    /**
     * 单管线结果。
     *
     * @param pipelineKey 管线 key
     * @param status      SUCCESS / FAILED
     * @param chunks      本管线片段数（FAILED 为 0）
     */
    public record PipelineOutcome(String pipelineKey, String status, int chunks) {
    }
}

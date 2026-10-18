package com.unirag.retrieval.web;

import com.unirag.retrieval.RetrievalService;
import com.unirag.retrieval.model.FailedTable;
import com.unirag.retrieval.model.RetrievalRequest;

import java.util.List;

/**
 * 检索响应体（contracts/retrieval-api.md §2）。
 *
 * @param traceId       可凭它在 {@code GET /api/v1/observations} 查出本次调用的各阶段记录
 * @param searchedTables 归并前实际检索的表数
 * @param failedTables   单表故障清单（原因摘要），其余表结果照常返回
 */
public record RetrieveResponse(String traceId,
                               String query,
                               String granularity,
                               List<ChunkView> chunks,
                               int searchedTables,
                               List<FailedTable> failedTables) {

    static RetrieveResponse of(RetrievalService.RetrievalResult result, RetrievalRequest request) {
        return new RetrieveResponse(result.traceId(), request.query(), request.granularity(),
                result.chunks().stream().map(ChunkView::of).toList(),
                result.searchedTables(), result.failedTables());
    }
}

package com.unirag.retrieval.stage;

import com.unirag.retrieval.model.FailedTable;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.RetrievedChunk;

import java.util.List;

/**
 * 归并阶段的输出：本次检索的请求要素 + 归并结果 + 表级故障清单。
 *
 * <p>保留 {@code request} 是因为后续阶段（提示词组装）需要原始问题文本，而归并结果本身不含它。
 */
public record MergeResult(RetrievalRequest request,
                          List<RetrievedChunk> chunks,
                          int searchedTables,
                          List<FailedTable> failedTables) {
}

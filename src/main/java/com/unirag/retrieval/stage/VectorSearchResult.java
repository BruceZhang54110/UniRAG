package com.unirag.retrieval.stage;

import com.unirag.retrieval.model.FailedTable;

import java.util.List;

/**
 * 逐表检索阶段的输出：命中的表 + 失败表清单（单表故障隔离，FR-009）。
 */
public record VectorSearchResult(QueryVector query, List<TableHits> hits, List<FailedTable> failedTables) {

    /** 归并前实际检索到的表数（失败表不计入）。 */
    public int searchedTables() {
        return hits.size();
    }
}

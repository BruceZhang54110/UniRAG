package com.unirag.retrieval.web;

import com.unirag.retrieval.QaService;

import java.util.List;

/**
 * 问答响应体（contracts/retrieval-api.md §3）。
 *
 * @param citations    顺序与提示词中引用标注一致；降级时为空数组
 * @param degraded     检索为空时为 {@code true}
 * @param degradeReason 降级原因，正常时为 {@code null}
 */
public record AskResponse(String traceId,
                          String answer,
                          List<ChunkView> citations,
                          boolean degraded,
                          String degradeReason) {

    static AskResponse of(QaService.QaResult result) {
        return new AskResponse(result.traceId(), result.answer().answer(),
                result.answer().citations().stream().map(ChunkView::of).toList(),
                result.answer().degraded(), result.answer().degradeReason());
    }
}

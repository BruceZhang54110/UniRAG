package com.unirag.retrieval.model;

import java.util.List;

/**
 * 问答结果（data-model.md §5，contracts/retrieval-api.md §3）：回答文本 + 引用清单 + 降级语义。
 *
 * @param citations    顺序与提示词中引用标注一致；只含回答实际引用到的片段，
 *                     回答未标注时回退为全部检索片段
 * @param degradeReason 取值见 {@link #NO_CONTEXT}
 */
public record QaAnswer(String answer, List<RetrievedChunk> citations, boolean degraded, String degradeReason) {

    /** 检索为空时的降级原因：MUST NOT 让模型在无上下文时自由作答（FR-005）。 */
    public static final String NO_CONTEXT = "NO_CONTEXT";

    /** 检索为空时返回的固定文案，替代模型生成（FR-005 的"明确告知未找到相关内容"）。 */
    public static final String NO_CONTEXT_ANSWER = "知识库中未找到相关内容，无法基于现有资料回答该问题。";

    public static QaAnswer noContext() {
        return new QaAnswer(NO_CONTEXT_ANSWER, List.of(), true, NO_CONTEXT);
    }
}

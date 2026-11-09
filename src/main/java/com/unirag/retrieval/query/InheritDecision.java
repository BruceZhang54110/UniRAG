package com.unirag.retrieval.query;

/**
 * 引用继承判定结果（023 data-model.md）：resolver 唯一输出，承载"检索限定到哪个文档 +
 * 为什么"两个信息，供执行、观测、降级、测试四方消费。除 {@link #INHERITED} 外 documentId 恒为
 * null——检索行为要么"限定单文档"要么"全库"，不存在中间态；任何存储/解析异常收敛为
 * {@link #RESOLVER_ERROR}（fail-open），resolver 不抛出。
 *
 * @param documentId  被继承的文档标识；非继承原因时为 null
 * @param reason      判定原因（8 值）
 * @param matchedTerm 命中的指代表达式；未命中为 null
 */
public record InheritDecision(Long documentId, Reason reason, String matchedTerm) {

    public enum Reason {
        NO_SESSION,
        NO_ANAPHORA,
        NO_PREVIOUS_ROUND,
        PREVIOUS_NOT_CITABLE,
        AMBIGUOUS_CITATIONS,
        DOCUMENT_DELETED,
        RESOLVER_ERROR,
        INHERITED
    }

    public static InheritDecision inherited(Long documentId, String matchedTerm) {
        if (documentId == null) {
            throw new IllegalArgumentException("INHERITED 决策必须携带 documentId");
        }
        return new InheritDecision(documentId, Reason.INHERITED, matchedTerm);
    }

    public static InheritDecision skipped(Reason reason, String matchedTerm) {
        if (reason == null || reason == Reason.INHERITED) {
            throw new IllegalArgumentException("skipped 决策不允许使用 " + reason);
        }
        return new InheritDecision(null, reason, matchedTerm);
    }
}

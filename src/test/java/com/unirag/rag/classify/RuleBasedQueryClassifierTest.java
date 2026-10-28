package com.unirag.rag.classify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** rules-v1 确定性分类器单元验收。 */
class RuleBasedQueryClassifierTest {

    private final RuleBasedQueryClassifier classifier = new RuleBasedQueryClassifier();

    @Test
    void 直接事实问题命中简单事实证据且置信度达标() {
        ClassificationOutcome outcome = classifier.classify("什么是租户隔离？");

        assertThat(outcome.status()).isEqualTo(ClassificationStatus.SUCCESS);
        assertThat(outcome.intent()).isEqualTo(QueryIntent.SIMPLE_FACT);
        assertThat(outcome.confidence()).isEqualTo(0.73);
        assertThat(outcome.ruleVersion()).isEqualTo("rules-v1");
        assertThat(outcome.evidence()).extracting(ClassificationEvidence::code)
                .containsExactly("SF_DIRECT_FACT");
        assertThat(outcome.reasonCode()).isEqualTo("RULE_MATCH");
    }

    @Test
    void 多跳关系问题优先选择图目标意图() {
        ClassificationOutcome outcome = classifier.classify("向量数据库与租户隔离之间有什么关系？");

        assertThat(outcome.status()).isEqualTo(ClassificationStatus.SUCCESS);
        assertThat(outcome.intent()).isEqualTo(QueryIntent.MULTI_HOP_RELATION);
        assertThat(outcome.confidence()).isEqualTo(0.73);
        assertThat(outcome.evidence()).extracting(ClassificationEvidence::code)
                .containsExactly("MH_RELATION");
    }

    @Test
    void 复杂推理证据达到优先阈值时选择复杂意图() {
        ClassificationOutcome outcome = classifier.classify("请先比较两种摄取方案，再结合知识库内容给出逐步实施建议。");

        assertThat(outcome.status()).isEqualTo(ClassificationStatus.SUCCESS);
        assertThat(outcome.intent()).isEqualTo(QueryIntent.COMPLEX_REASONING);
        assertThat(outcome.confidence()).isGreaterThanOrEqualTo(0.90);
        assertThat(outcome.evidence()).extracting(ClassificationEvidence::code)
                .contains("CR_COMPARE_DECIDE", "CR_MULTISTEP");
    }

    @Test
    void 无证据问题给弱简单事实证据并进入低置信度() {
        ClassificationOutcome outcome = classifier.classify("zzz-qwerty-asdfgh-unknown-object-identifier");

        assertThat(outcome.status()).isEqualTo(ClassificationStatus.LOW_CONFIDENCE);
        assertThat(outcome.intent()).isEqualTo(QueryIntent.SIMPLE_FACT);
        assertThat(outcome.confidence()).isEqualTo(0.52);
        assertThat(outcome.evidence()).extracting(ClassificationEvidence::code)
                .containsExactly("INSUFFICIENT_EVIDENCE");
        assertThat(outcome.reasonCode()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test
    void 相同输入产生完全一致结果且证据不引用问题原文() {
        String question = "什么是向量数据库？";

        ClassificationOutcome first = classifier.classify(question);
        ClassificationOutcome second = classifier.classify(question);

        assertThat(second).isEqualTo(first);
        assertThat(first.evidence()).allSatisfy(evidence ->
                assertThat(evidence.description()).doesNotContain(question));
    }
}

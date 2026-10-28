package com.unirag.rag.classify;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * rules-v1 确定性规则分类器（research.md §R5）。只使用当前问题的语言/结构证据，
 * 不调用模型、不访问数据库或知识库，也不改写原始问题。
 */
@Component
public class RuleBasedQueryClassifier implements QueryClassifier {

    static final String RULE_VERSION = "rules-v1";

    private static final List<Rule> LITERAL_RULES = List.of(
            literal("SF_DIRECT_FACT", QueryIntent.SIMPLE_FACT, 0.55,
                    "direct fact wording", "什么是", "是什么", "定义", "含义", "何时", "哪个版本", "多少",
                    "what is", "define", "when", "which version"),
            literal("SF_SINGLE_TARGET_HOW_TO", QueryIntent.SIMPLE_FACT, 0.45,
                    "single-target how-to wording", "如何配置", "怎么配置", "如何启用", "怎么使用", "在哪里",
                    "how to configure", "how do i", "where"),
            literal("MH_RELATION", QueryIntent.MULTI_HOP_RELATION, 0.55,
                    "relationship wording", "关系", "关联", "区别", "对应", "之间", "依赖", "映射",
                    "relationship", "relation", "depends on", "difference"),
            literal("MH_MULTI_ENTITY", QueryIntent.MULTI_HOP_RELATION, 0.35,
                    "multiple entity wording", "分别", "两个", "多个", "各自", "跨文档", "多份文档",
                    "across documents", "multiple"),
            literal("CR_MULTISTEP", QueryIntent.COMPLEX_REASONING, 0.60,
                    "multi-step planning wording", "逐步", "推理", "多步", "规划", "方案",
                    "step by step", "multi-step", "plan"),
            literal("CR_COMPARE_DECIDE", QueryIntent.COMPLEX_REASONING, 0.50,
                    "compare and decide wording", "计算", "calculate", "recommend", "choose", "select"),
            literal("CR_TOOL_COMBINATION", QueryIntent.COMPLEX_REASONING, 0.45,
                    "tool combination wording", "联网", "web_search", "知识图谱"));

    private static final List<Rule> REGEX_RULES = List.of(
            regex("MH_CHAIN", QueryIntent.MULTI_HOP_RELATION, 0.45,
                    "chained relation wording", "先.{0,40}再", "基于.{0,40}进一步", "分别.{0,40}并且",
                    "通过.{0,40}定位.{0,40}再", "then", "after that"),
            regex("MH_NESTED_REFERENCE", QueryIntent.MULTI_HOP_RELATION, 0.30,
                    "nested reference wording", "其.{0,20}的.{0,20}的", "该.{0,20}对应.{0,20}所属"),
            regex("CR_MULTISTEP", QueryIntent.COMPLEX_REASONING, 0.60,
                    "synthesize and present wording", "综合.{0,30}给出"),
            regex("CR_COMPARE_DECIDE", QueryIntent.COMPLEX_REASONING, 0.50,
                    "compare/recommend wording", "对比.{0,50}(建议|选择|recommend|choose|select)",
                    "比较.{0,40}给出", "评估.{0,40}选择",
                    "compare.{0,50}(recommend|choose|select)"),
            regex("CR_TOOL_COMBINATION", QueryIntent.COMPLEX_REASONING, 0.45,
                    "combined retrieval/tool wording", "向量.{0,30}(并且|再).{0,30}搜索",
                    "向量.{0,30}搜索.{0,30}再", "检索.{0,30}再.{0,30}计算",
                    "调用.{0,30}模型.{0,30}最后"),
            regex("CR_CONDITIONAL", QueryIntent.COMPLEX_REASONING, 0.35,
                    "conditional reasoning wording", "如果.{0,60}那么", "在.{0,50}条件下",
                    "在.{0,40}条件下.{0,40}是否", "满足.{0,40}所有",
                    "满足.{0,40}所有.{0,40}并且", "if.{0,80}then"));

    @Override
    public ClassificationOutcome classify(String question) {
        long startedAt = System.nanoTime();
        String normalized = question.strip().toLowerCase(Locale.ROOT);
        Map<QueryIntent, Double> scores = new EnumMap<>(QueryIntent.class);
        scores.put(QueryIntent.SIMPLE_FACT, 0.0);
        scores.put(QueryIntent.MULTI_HOP_RELATION, 0.0);
        scores.put(QueryIntent.COMPLEX_REASONING, 0.0);
        List<ClassificationEvidence> evidence = new ArrayList<>();

        for (Rule rule : LITERAL_RULES) {
            if (rule.matches(normalized)) {
                addEvidence(rule, evidence, scores);
            }
        }
        for (Rule rule : REGEX_RULES) {
            if (rule.matches(normalized)) {
                addEvidence(rule, evidence, scores);
            }
        }

        if (evidence.isEmpty() && normalized.length() <= 30) {
            addEvidence(new Rule("SF_SHORT_LOOKUP", QueryIntent.SIMPLE_FACT, 0.30,
                    "short lookup without competing evidence", List.of(), List.of()), evidence, scores);
        } else if (evidence.isEmpty()) {
            addEvidence(new Rule("INSUFFICIENT_EVIDENCE", QueryIntent.SIMPLE_FACT, 0.20,
                    "insufficient structural or language evidence", List.of(), List.of()), evidence, scores);
        }

        QueryIntent winner = chooseIntent(scores);
        double winnerScore = scores.get(winner);
        double runnerUpScore = scores.entrySet().stream()
                .filter(entry -> entry.getKey() != winner)
                .mapToDouble(Map.Entry::getValue)
                .max()
                .orElse(0.0);
        double confidence = clamp(0.40 + 0.60 * winnerScore - 0.20 * runnerUpScore, 0.0, 0.99);
        if (winnerScore < 0.45) {
            confidence = Math.min(confidence, 0.69);
        }
        confidence = Math.round(confidence * 100.0) / 100.0;
        boolean lowConfidence = confidence < 0.70;
        String reasonCode = reasonCode(evidence, lowConfidence);
        return new ClassificationOutcome(
                lowConfidence ? ClassificationStatus.LOW_CONFIDENCE : ClassificationStatus.SUCCESS,
                winner, confidence, evidence, reasonCode, RULE_VERSION, elapsedMillis(startedAt));
    }

    private static void addEvidence(Rule rule, List<ClassificationEvidence> evidence,
                                    Map<QueryIntent, Double> scores) {
        evidence.add(new ClassificationEvidence(rule.code(), rule.intent(), rule.weight(), rule.description()));
        scores.merge(rule.intent(), rule.weight(), Double::sum);
    }

    private static QueryIntent chooseIntent(Map<QueryIntent, Double> scores) {
        if (scores.get(QueryIntent.COMPLEX_REASONING) >= 0.60) {
            return QueryIntent.COMPLEX_REASONING;
        }
        QueryIntent winner = QueryIntent.COMPLEX_REASONING;
        for (QueryIntent intent : List.of(QueryIntent.MULTI_HOP_RELATION, QueryIntent.SIMPLE_FACT)) {
            if (scores.get(intent) > scores.get(winner)) {
                winner = intent;
            }
        }
        return winner;
    }

    private static String reasonCode(List<ClassificationEvidence> evidence, boolean lowConfidence) {
        if (!lowConfidence) {
            return "RULE_MATCH";
        }
        return evidence.stream().anyMatch(item -> item.code().equals("INSUFFICIENT_EVIDENCE"))
                ? "INSUFFICIENT_EVIDENCE"
                : "LOW_CONFIDENCE";
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static Rule literal(String code, QueryIntent intent, double weight, String description,
                                String... literals) {
        return new Rule(code, intent, weight, description, List.of(literals), List.of());
    }

    private static Rule regex(String code, QueryIntent intent, double weight, String description,
                              String... patterns) {
        List<Pattern> compiled = java.util.Arrays.stream(patterns).map(Pattern::compile).toList();
        return new Rule(code, intent, weight, description, List.of(), compiled);
    }

    private record Rule(String code,
                        QueryIntent intent,
                        double weight,
                        String description,
                        List<String> literals,
                        List<Pattern> patterns) {

        private Rule {
            patterns = patterns == null ? List.of() : List.copyOf(patterns);
            literals = literals == null ? List.of() : List.copyOf(literals);
        }

        private boolean matches(String normalized) {
            for (String literal : literals) {
                if (normalized.contains(literal)) {
                    return true;
                }
            }
            for (Pattern pattern : patterns) {
                if (pattern.matcher(normalized).find()) {
                    return true;
                }
            }
            return false;
        }
    }
}

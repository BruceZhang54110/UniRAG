package com.unirag.retrieval.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 改写产物的确定性校验（contracts/query-rewrite.md §3.2）。
 *
 * <p>只做可确定判定的检查：空值、多行、长度、显式约束丢失。语义质量由提示词与评测集负责，
 * 不在这里做模糊判断。校验失败返回原因码，由扩展回退原问题并记录。
 */
final class RewriteValidator {

    static final String EMPTY_OUTPUT = "EMPTY_OUTPUT";

    static final String MULTIPLE_QUERIES = "MULTIPLE_QUERIES";

    static final String MULTILINE_OUTPUT = "MULTILINE_OUTPUT";

    static final String TOO_LONG = "TOO_LONG";

    static final String TOO_VERBOSE = "TOO_VERBOSE";

    static final String DROPPED_CONSTRAINT = "DROPPED_CONSTRAINT";

    private static final Pattern NUMBER_OR_DATE = Pattern.compile("\\d+(?:[.,:/-]\\d+)*");

    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"([^\"\\n]{1,100})\"|“([^”\\n]{1,100})”");

    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^'\\n]{1,100})'|‘([^’\\n]{1,100})’");

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\n]{1,200})`");

    private RewriteValidator() {
    }

    static Optional<String> validate(String original, String rewritten, QueryRewriteProperties properties) {
        String text = rewritten.strip();
        if (text.contains("\n") || text.contains("\r")) {
            return Optional.of(MULTILINE_OUTPUT);
        }
        if (text.length() > properties.maxOutputChars()) {
            return Optional.of(TOO_LONG);
        }
        if (text.length() > original.length() * properties.maxOutputRatio()) {
            return Optional.of(TOO_VERBOSE);
        }
        if (!preservesConstraints(original, text)) {
            return Optional.of(DROPPED_CONSTRAINT);
        }
        return Optional.empty();
    }

    private static boolean preservesConstraints(String original, String rewritten) {
        for (String token : constraints(original)) {
            if (!rewritten.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /** 提取数字/日期、引号内短语、URL、反引号代码片段；只覆盖可确定判定的形态。 */
    private static List<String> constraints(String original) {
        List<String> tokens = new ArrayList<>();
        addMatches(tokens, NUMBER_OR_DATE, original, 0);
        addMatches(tokens, DOUBLE_QUOTED, original, 1, 2);
        addMatches(tokens, SINGLE_QUOTED, original, 1, 2);
        addMatches(tokens, URL, original, 0);
        addMatches(tokens, CODE_SPAN, original, 1);
        return tokens;
    }

    private static void addMatches(List<String> tokens, Pattern pattern, String text, int... groups) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            for (int group : groups) {
                String value = matcher.group(group);
                if (value != null && !value.isBlank() && !tokens.contains(value)) {
                    tokens.add(value);
                }
            }
        }
    }
}

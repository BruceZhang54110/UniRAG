package com.unirag.eval.metric;

import java.text.Normalizer;
import java.util.Locale;

/** 标注与片段匹配共用的文本归一化（013 contracts/dataset.md §5）。 */
public final class NormalizedText {

    private NormalizedText() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = normalized.replaceAll("\\s+", " ").strip();
        return normalized.toLowerCase(Locale.ROOT);
    }

    public static boolean contains(String text, String span) {
        String normalizedText = normalize(text);
        String normalizedSpan = normalize(span);
        return !normalizedSpan.isEmpty() && normalizedText.contains(normalizedSpan);
    }
}

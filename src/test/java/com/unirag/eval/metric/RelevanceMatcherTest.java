package com.unirag.eval.metric;

import com.unirag.eval.dataset.QueryCase;
import com.unirag.eval.dataset.RelevanceLabel;
import com.unirag.retrieval.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelevanceMatcherTest {

    private final RelevanceMatcher matcher = new RelevanceMatcher();

    @Test
    void normalizesWhitespaceCaseAndFullWidth() {
        assertEquals("hello world", NormalizedText.normalize("  ＨＥＬＬＯ\n\tWorld  "));
        assertTrue(NormalizedText.contains("距离算子  是\n余弦", "距离算子 是 余弦"));
    }

    @Test
    void matchesDocumentAndSpan() {
        RetrievedChunk chunk = chunk("doc.md", "fine", "这里包含目标片段，还有别的内容。", 0.9);
        QueryCase query = query(List.of(new RelevanceLabel("doc.md", "目标片段")));

        assertTrue(matcher.matches(chunk, query.relevant().get(0)));
        assertTrue(matcher.matchesAny(chunk, query));
        assertTrue(matcher.documentMatches(chunk, query));
        assertEquals(1, matcher.matchedLabels(List.of(chunk), query.relevant()));
        assertEquals(1, matcher.firstRelevantRank(List.of(chunk), query));
    }

    @Test
    void rejectsDifferentDocumentOrMissingSpan() {
        QueryCase query = query(List.of(new RelevanceLabel("doc.md", "目标片段")));
        assertFalse(matcher.matches(chunk("other.md", "fine", "目标片段", 0.9), query.relevant().get(0)));
        assertFalse(matcher.matches(chunk("doc.md", "fine", "无关内容", 0.9), query.relevant().get(0)));
    }

    @Test
    void deduplicatesGoldDocuments() {
        QueryCase query = query(List.of(
                new RelevanceLabel("doc.md", "目标片段"),
                new RelevanceLabel("doc.md", "另一片段")));
        Set<String> docs = matcher.goldDocuments(query);
        assertEquals(Set.of("doc.md"), docs);
    }

    private static QueryCase query(List<RelevanceLabel> labels) {
        return new QueryCase("q-1", "问题？", List.of("fact"), "dev", true, labels);
    }

    private static RetrievedChunk chunk(String doc, String pipeline, String text, double score) {
        return new RetrievedChunk(1L, doc, pipeline, null, 0, score, text);
    }
}

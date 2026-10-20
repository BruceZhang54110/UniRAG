package com.unirag.eval.metric;

import com.unirag.eval.dataset.QueryCase;
import com.unirag.eval.dataset.RelevanceLabel;
import com.unirag.retrieval.model.RetrievedChunk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 相关性匹配：文档 + 归一化原文片段（013 data-model §3）。 */
public final class RelevanceMatcher {

    public boolean matches(RetrievedChunk chunk, RelevanceLabel label) {
        return chunk.docName() != null
                && chunk.docName().equals(label.doc())
                && NormalizedText.contains(chunk.text(), label.span());
    }

    public boolean matchesAny(RetrievedChunk chunk, QueryCase query) {
        return query.relevant().stream().anyMatch(label -> matches(chunk, label));
    }

    public boolean documentMatches(RetrievedChunk chunk, QueryCase query) {
        return query.relevant().stream().anyMatch(label -> label.doc().equals(chunk.docName()));
    }

    public Set<String> goldDocuments(QueryCase query) {
        Set<String> docs = new HashSet<>();
        query.relevant().forEach(label -> docs.add(label.doc()));
        return docs;
    }

    public int matchedLabels(List<RetrievedChunk> retrieved, List<RelevanceLabel> labels) {
        int matched = 0;
        for (RelevanceLabel label : labels) {
            if (retrieved.stream().anyMatch(chunk -> matches(chunk, label))) {
                matched++;
            }
        }
        return matched;
    }

    public int firstRelevantRank(List<RetrievedChunk> retrieved, QueryCase query) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (matchesAny(retrieved.get(i), query)) {
                return i + 1;
            }
        }
        return -1;
    }
}

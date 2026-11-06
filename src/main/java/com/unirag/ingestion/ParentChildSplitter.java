package com.unirag.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 父子两级切分器（020 research.md R1）：第一级按父块上限切（不重叠），第二级在每个父块内部
 * 按子块参数切（含重叠），子块绝不跨父块。
 *
 * <p>{@link #splitWithParents(Document)} 是摄取侧使用的权威出口（需要父子归属）；
 * {@link #split(Document)} 按接口约定摊平输出（metadata 携带父块序号），供不需要归属的调用方。
 */
public final class ParentChildSplitter implements DocumentSplitter {

    /** 一个父块及其子块文本（子块不跨父块）。 */
    public record ParentSplit(String parentText, List<String> childTexts) {
    }

    static final String META_PARENT_SEQ_INDEX = "parent_seq_index";

    private final DocumentSplitter parentSplitter;
    private final DocumentSplitter childSplitter;

    public ParentChildSplitter(int maxChildTokens, int overlapTokens, int parentMaxTokens,
                               TokenCountEstimator tokenizer) {
        if (parentMaxTokens <= maxChildTokens) {
            throw new IllegalArgumentException(
                    "parent_max_tokens must be greater than max_segment_tokens (child size)");
        }
        this.parentSplitter = DocumentSplitters.recursive(parentMaxTokens, 0, tokenizer);
        this.childSplitter = DocumentSplitters.recursive(maxChildTokens, overlapTokens, tokenizer);
    }

    /** 两级切分：返回父块列表，每个父块携带其子块文本。 */
    public List<ParentSplit> splitWithParents(Document document) {
        List<TextSegment> parents = parentSplitter.split(document);
        List<ParentSplit> splits = new ArrayList<>(parents.size());
        for (TextSegment parent : parents) {
            List<TextSegment> children = childSplitter.split(Document.from(parent.text()));
            splits.add(new ParentSplit(parent.text(),
                    children.stream().map(TextSegment::text).toList()));
        }
        return splits;
    }

    @Override
    public List<TextSegment> split(Document document) {
        List<ParentSplit> splits = splitWithParents(document);
        List<TextSegment> flat = new ArrayList<>();
        for (int parentIndex = 0; parentIndex < splits.size(); parentIndex++) {
            ParentSplit split = splits.get(parentIndex);
            for (String childText : split.childTexts()) {
                dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
                metadata.put(META_PARENT_SEQ_INDEX, parentIndex);
                flat.add(TextSegment.from(childText, metadata));
            }
        }
        return flat;
    }
}

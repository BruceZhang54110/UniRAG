package com.unirag.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构感知切分器（021 research.md R2/R4）：章节充当 020 的"父块角色"——
 * 每个章节正文按父上限切出 PARENT 候选（超大章节多个），其内按子块参数切 CHILD；
 * 所有父子切片携带相同 section_path，落库与回填机制与 020 完全对齐。
 *
 * <p>全文零标题（{@link #fallback()}）时由调用方走单级递归等价回退。
 */
public final class StructureAwareSplitter implements DocumentSplitter {

    /** 一个切分单元的映射结果：章节路径 + 父块及子块文本（与 020 ParentSplit 同构）。 */
    public record SectionSplit(String sectionPath, List<ParentChildSplitter.ParentSplit> parentSplits) {
    }

    private final SectionParser sectionParser = new SectionParser();

    private final int maxChildTokens;

    private final int overlapTokens;

    private final int parentMaxTokens;

    private final TokenCountEstimator tokenizer;

    public StructureAwareSplitter(int maxChildTokens, int overlapTokens, int parentMaxTokens,
                                  TokenCountEstimator tokenizer) {
        if (parentMaxTokens <= maxChildTokens) {
            throw new IllegalArgumentException(
                    "parent_max_tokens must be greater than max_segment_tokens (child size)");
        }
        this.maxChildTokens = maxChildTokens;
        this.overlapTokens = overlapTokens;
        this.parentMaxTokens = parentMaxTokens;
        this.tokenizer = tokenizer;
    }

    /** 权威出口：按章节产出"路径 → 父子切片"映射（摄取侧使用）；零标题时 fallback=true。 */
    public StructureSplit splitWithStructure(Document document) {
        List<SectionParser.Section> sections = sectionParser.parse(document.text());
        boolean fallback = sections.size() == 1 && sections.get(0).level() == 0
                && sections.get(0).path().isEmpty();
        List<SectionSplit> splits = new ArrayList<>(sections.size());
        for (SectionParser.Section section : sections) {
            splits.add(new SectionSplit(section.path(), splitSectionBody(section.body())));
        }
        return new StructureSplit(fallback, splits);
    }

    /** 解析 + 切分结果：fallback=true 表示全文零标题，调用方应走递归等价回退。 */
    public record StructureSplit(boolean fallback, List<SectionSplit> splits) {
    }

    /** 章节正文 → 父块候选列表（复用 020 父子切分器：小章节单父块，超大章节多父块）。 */
    private List<ParentChildSplitter.ParentSplit> splitSectionBody(String body) {
        DocumentSplitter parentSplitter = DocumentSplitters.recursive(parentMaxTokens, 0, tokenizer);
        DocumentSplitter childSplitter = DocumentSplitters.recursive(maxChildTokens, overlapTokens, tokenizer);
        List<TextSegment> parents = parentSplitter.split(Document.from(body));
        List<ParentChildSplitter.ParentSplit> splits = new ArrayList<>(parents.size());
        for (TextSegment parent : parents) {
            List<TextSegment> children = childSplitter.split(Document.from(parent.text()));
            splits.add(new ParentChildSplitter.ParentSplit(parent.text(),
                    children.stream().map(TextSegment::text).toList()));
        }
        return splits;
    }

    @Override
    public List<TextSegment> split(Document document) {
        List<TextSegment> flat = new ArrayList<>();
        for (SectionSplit sectionSplit : splitWithStructure(document).splits()) {
            for (ParentChildSplitter.ParentSplit parentSplit : sectionSplit.parentSplits()) {
                for (String childText : parentSplit.childTexts()) {
                    flat.add(TextSegment.from(childText));
                }
            }
        }
        return flat;
    }
}

package com.unirag.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ParentChildSplitter 单测（tasks.md T008）：父子归属、子块不跨父块、短文档父子重合、
 * 父块 overlap 为 0、split() 摊平 metadata 父块序号、构造参数校验。
 */
class ParentChildSplitterTest {

    private final IngestionTokenizer tokenizer = new IngestionTokenizer();

    @Test
    void childrenNeverCrossParentBoundary() {
        ParentChildSplitter splitter = new ParentChildSplitter(50, 10, 200, tokenizer);
        List<ParentChildSplitter.ParentSplit> splits = splitter.splitWithParents(Document.from(multiParagraphDoc()));

        assertThat(splits).hasSizeGreaterThan(1);
        int totalChildren = 0;
        for (ParentChildSplitter.ParentSplit split : splits) {
            assertThat(split.childTexts()).isNotEmpty();
            for (String child : split.childTexts()) {
                assertThat(split.parentText())
                        .as("子块必须完整出现在其所属父块内（不跨父块）")
                        .contains(child.strip());
                totalChildren++;
            }
        }
        assertThat(totalChildren).isGreaterThan(splits.size());
    }

    @Test
    void shortDocumentYieldsSingleParentAndSingleChild() {
        ParentChildSplitter splitter = new ParentChildSplitter(300, 40, 1200, tokenizer);
        List<ParentChildSplitter.ParentSplit> splits =
                splitter.splitWithParents(Document.from("这是一段很短的文档内容，不足以切出多个片段。"));

        assertThat(splits).hasSize(1);
        assertThat(splits.get(0).childTexts()).hasSize(1);
        assertThat(splits.get(0).childTexts().get(0).strip())
                .isEqualTo(splits.get(0).parentText().strip());
    }

    @Test
    void parentsHaveNoOverlap() {
        // 同样内容用 50 token 子块参数跑单级 recursive 作对照：父子策略父块数应更少（无重叠）
        String content = multiParagraphDoc();
        ParentChildSplitter splitter = new ParentChildSplitter(50, 10, 200, tokenizer);
        int parentCount = splitter.splitWithParents(Document.from(content)).size();
        int flatCount = DocumentSplitterFactory
                .create("RECURSIVE", 200, 50, tokenizer)
                .split(Document.from(content))
                .size();
        assertThat(parentCount).isLessThanOrEqualTo(flatCount);
    }

    @Test
    void flattenCarriesMonotonicParentIndex() {
        ParentChildSplitter splitter = new ParentChildSplitter(50, 10, 200, tokenizer);
        List<TextSegment> flat = splitter.split(Document.from(multiParagraphDoc()));

        int previousParent = -1;
        for (TextSegment segment : flat) {
            Integer parentIndex = segment.metadata().getInteger(ParentChildSplitter.META_PARENT_SEQ_INDEX);
            assertThat(parentIndex).isNotNull();
            assertThat(parentIndex).isGreaterThanOrEqualTo(previousParent);
            previousParent = parentIndex;
        }
    }

    @Test
    void rejectsParentNotGreaterThanChild() {
        assertThatThrownBy(() -> new ParentChildSplitter(300, 30, 300, tokenizer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_max_tokens");
        assertThatThrownBy(() -> new ParentChildSplitter(300, 30, 100, tokenizer))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 约 20 个段落、段落间空行，供 recursive 切分器按段落/句子降级切分。 */
    private static String multiParagraphDoc() {
        StringBuilder doc = new StringBuilder();
        IntStream.rangeClosed(1, 20).forEach(i -> doc
                .append("第").append(i).append("条 本条款规定某一具体业务事项的处理流程与责任归属，")
                .append("要求相关部门在收到申请后的规定时限内完成审核并给予书面答复。")
                .append("逾期未处理的，申请人可以向上级主管机构提出申诉，主管机构应当予以登记并在十五个工作日内办结。\n\n"));
        return doc.toString();
    }
}

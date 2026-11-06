package com.unirag.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SectionParser 单测（tasks.md T008）：中文多级编号路径组装、Markdown 层级、
 * 代码块/表格内样式不误判、句读/超长行不误判、跳空层级、前言保留、空章节、零标题回退。
 */
class SectionParserTest {

    private final SectionParser parser = new SectionParser();

    @Test
    void chineseMultiLevelPathAssembly() {
        String doc = """
                前言说明文字。

                一、总体要求
                坚持基本原则。

                （一）基本原则
                具体内容甲。

                （二）发展目标
                具体内容乙。

                二、重点任务
                任务内容。
                """;
        List<SectionParser.Section> sections = parser.parse(doc);
        assertThat(sections).hasSize(5);
        assertThat(sections.get(0).path()).isEmpty();
        assertThat(sections.get(0).body()).contains("前言说明文字");
        assertThat(sections.get(1).path()).isEqualTo("一、总体要求");
        assertThat(sections.get(2).path()).isEqualTo("一、总体要求/（一）基本原则");
        assertThat(sections.get(3).path()).isEqualTo("一、总体要求/（二）发展目标");
        assertThat(sections.get(4).path()).isEqualTo("二、重点任务");
        // 兄弟标题（二、发展目标）弹出（一）子栈
        assertThat(sections.get(4).level()).isEqualTo(1);
        // 章节正文不含标题行
        assertThat(sections.get(2).body()).doesNotContain("（一）基本原则");
    }

    @Test
    void markdownHeadingLevelsAndFenceExclusion() {
        String doc = """
                # 标题一
                内容一。

                ```java
                # 注释不是标题
                ```

                ## 小节甲
                内容二。

                | # 表内文本 | 值 |
                |---|---|

                # 标题二
                内容三。
                """;
        List<SectionParser.Section> sections = parser.parse(doc);
        assertThat(sections).extracting(SectionParser.Section::path)
                .containsExactly("标题一", "标题一/小节甲", "标题二");
        assertThat(sections.get(2).level()).isEqualTo(1);
    }

    @Test
    void sentenceLikeNumberedLinesAreNotHeadings() {
        String doc = """
                一、总体要求
                正文。

                1. 首先我们要明确目标，这是第一步，需要详细展开说明并且在同一句内继续论述。
                2. 其次我们要落实责任，这也是很长的一行说明文字，明显不是标题而是正文列表项。
                """;
        List<SectionParser.Section> sections = parser.parse(doc);
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).path()).isEqualTo("一、总体要求");
        assertThat(sections.get(0).body()).contains("1. 首先我们要明确目标");
    }

    @Test
    void skippedLevelsNotBackfilled() {
        String doc = """
                一、章节
                甲内容。

                （三）直接出现的子节
                乙内容。
                """;
        List<SectionParser.Section> sections = parser.parse(doc);
        assertThat(sections.get(1).path()).isEqualTo("一、章节/（三）直接出现的子节");
    }

    @Test
    void emptySectionProducesNoSection() {
        String doc = """
                一、章节
                甲内容。

                二、空章

                三、后续
                乙内容。
                """;
        List<SectionParser.Section> sections = parser.parse(doc);
        assertThat(sections).extracting(SectionParser.Section::path)
                .containsExactly("一、章节", "三、后续");
    }

    @Test
    void noHeadingsYieldsSingleFallbackSection() {
        List<SectionParser.Section> sections =
                parser.parse("没有任何标题的普通段落。\n\n第二个段落。");
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).path()).isEmpty();
        assertThat(sections.get(0).level()).isZero();
        assertThat(sections.get(0).body()).contains("没有任何标题");
    }

    @Test
    void chapterPrefixVariantsDetected() {
        String doc = """
                第一章 概述
                内容。

                第十条 具体规定
                规定内容。
                """;
        List<SectionParser.Section> sections = parser.parse(doc);
        assertThat(sections).extracting(SectionParser.Section::path)
                .containsExactly("第一章 概述", "第十条 具体规定");
    }
}

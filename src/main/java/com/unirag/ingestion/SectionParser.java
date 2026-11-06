package com.unirag.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 章节解析器（021 contracts §1）：行扫描识别文档章节骨架，两种体系自动择一——
 * Markdown 标题（#{1,6}）与中文公文编号（第N条/章/节、一、（一）、1.）。
 *
 * <p>防误判契约：Markdown 检测排除围栏代码块（``` 开关状态机）与表格行（行首 |）；
 * 中文编号候选行须 ≤30 字且不以句读结尾；全文零标题时返回单节（空路径），由调用方回退。
 * 层级栈组织路径：新标题弹出 ≥ 本级栈顶，跳空层级不补全；首个标题前的内容为前言（空路径）。
 */
public final class SectionParser {

    /** 一个章节：层级、标题、累积路径、正文（标题行之后到下一标题前的文本）。 */
    public record Section(int level, String title, String path, String body) {
    }

    private static final int MAX_HEADING_CHARS = 30;

    private static final String SENTENCE_ENDINGS = "。！？；，,.?!;";

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+\\S.*");

    private static final Pattern CN_CHAPTER = Pattern.compile("^第[一二三四五六七八九十百零]+[条章节].*");

    private static final Pattern CN_LEVEL1 = Pattern.compile("^[一二三四五六七八九十]+、.*");

    private static final Pattern CN_LEVEL2 = Pattern.compile("^（[一二三四五六七八九十]+）.*");

    private static final Pattern CN_LEVEL3 = Pattern.compile("^\\d+[.、]\\s*\\S.*");

    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*");

    /** 解析全文为章节列表（跳过空正文章节）；零标题时返回单节（level=0, path=""）。 */
    public List<Section> parse(String content) {
        String[] lines = content.split("\n", -1);
        boolean markdown = countMarkdownHeadings(lines) >= countChineseHeadings(lines);
        List<Heading> headings = markdown ? markdownHeadings(lines) : chineseHeadings(lines);

        List<Section> sections = new ArrayList<>();
        if (headings.isEmpty()) {
            sections.add(new Section(0, "", "", content));
            return sections;
        }

        List<String> stackTitles = new ArrayList<>();
        List<Integer> stackLevels = new ArrayList<>();
        int bodyStart = 0;
        for (Heading heading : headings) {
            appendSection(sections, stackTitles, stackLevels, lines, bodyStart, heading.lineIndex);
            while (!stackLevels.isEmpty() && stackLevels.get(stackLevels.size() - 1) >= heading.level) {
                stackLevels.remove(stackLevels.size() - 1);
                stackTitles.remove(stackTitles.size() - 1);
            }
            stackLevels.add(heading.level);
            stackTitles.add(heading.title);
            bodyStart = heading.lineIndex + 1;
        }
        appendSection(sections, stackTitles, stackLevels, lines, bodyStart, lines.length);
        return sections;
    }

    /** 前言/章节的正文提取：行区间拼接（非空才产出）；path 为当前栈的累积路径。 */
    private static void appendSection(List<Section> sections, List<String> stackTitles,
                                      List<Integer> stackLevels, String[] lines, int from, int to) {
        String body = joinLines(lines, from, to);
        if (body.isBlank()) {
            return;
        }
        boolean preamble = stackTitles.isEmpty();
        int level = preamble ? 0 : stackLevels.get(stackLevels.size() - 1);
        String title = preamble ? "" : stackTitles.get(stackTitles.size() - 1);
        sections.add(new Section(level, title, String.join("/", stackTitles), body));
    }

    private static String joinLines(String[] lines, int from, int to) {
        StringBuilder body = new StringBuilder();
        for (int i = from; i < to; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(line);
        }
        return body.toString();
    }

    private int countMarkdownHeadings(String[] lines) {
        return markdownHeadings(lines).size();
    }

    private int countChineseHeadings(String[] lines) {
        return chineseHeadings(lines).size();
    }

    private List<Heading> markdownHeadings(String[] lines) {
        List<Heading> headings = new ArrayList<>();
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (FENCE.matcher(line).matches()) {
                inFence = !inFence;
                continue;
            }
            if (inFence || line.startsWith("|")) {
                continue;
            }
            java.util.regex.Matcher matcher = MARKDOWN_HEADING.matcher(line.strip());
            if (matcher.matches()) {
                headings.add(new Heading(i, matcher.group(1).length(),
                        line.strip().substring(matcher.group(1).length()).strip()));
            }
        }
        return headings;
    }

    private List<Heading> chineseHeadings(String[] lines) {
        List<Heading> headings = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].strip();
            if (stripped.isEmpty() || stripped.length() > MAX_HEADING_CHARS
                    || SENTENCE_ENDINGS.indexOf(stripped.charAt(stripped.length() - 1)) >= 0) {
                continue;
            }
            int level = chineseLevel(stripped);
            if (level > 0) {
                headings.add(new Heading(i, level, stripped));
            }
        }
        return headings;
    }

    /** 中文编号层级映射：第N条/章/节 → 1；一、 → 1；（一） → 2；1. → 3。 */
    private static int chineseLevel(String line) {
        if (CN_CHAPTER.matcher(line).matches() || CN_LEVEL1.matcher(line).matches()) {
            return 1;
        }
        if (CN_LEVEL2.matcher(line).matches()) {
            return 2;
        }
        if (CN_LEVEL3.matcher(line).matches()) {
            return 3;
        }
        return 0;
    }

    private record Heading(int lineIndex, int level, String title) {
    }
}

package com.unirag.eval;

import java.util.List;
import java.util.Map;

/**
 * 结构对齐指标（021 FR-010，research.md R6）：对一次摄取结果计算跨章节边界率与章节路径覆盖率。
 *
 * @param boundaryCrossingRate 含任一标题行的 chunk 占比（结构感知策略应恒为 0）
 * @param sectionPathCoverage  带非空 section_path 的 chunk 占比（结构感知策略应为 1.0）
 */
public record StructureAlignmentMetrics(double boundaryCrossingRate, double sectionPathCoverage) {

    /** 单 chunk 的可观测视图（content + section_path）。 */
    public record ChunkView(String content, String sectionPath) {
    }

    /**
     * 计算指标。
     *
     * @param headings 语料文档的标题清单（按出现顺序，标题原文）
     * @param chunks   摄取后该文档的全部 chunk（PARENT 与 CHILD 都参与）
     */
    public static StructureAlignmentMetrics compute(List<String> headings, List<ChunkView> chunks) {
        if (chunks.isEmpty()) {
            return new StructureAlignmentMetrics(0.0, 0.0);
        }
        long crossing = chunks.stream()
                .filter(chunk -> containsAnyHeading(chunk.content(), headings))
                .count();
        long covered = chunks.stream()
                .filter(chunk -> chunk.sectionPath() != null)
                .count();
        return new StructureAlignmentMetrics(crossing / (double) chunks.size(),
                covered / (double) chunks.size());
    }

    private static boolean containsAnyHeading(String content, List<String> headings) {
        if (content == null) {
            return false;
        }
        for (String heading : headings) {
            if (heading != null && !heading.isEmpty() && content.contains(heading)) {
                return true;
            }
        }
        return false;
    }

    /** 从 DB 行（content/section_path 列）构造视图。 */
    public static ChunkView view(Map<String, Object> row) {
        return new ChunkView((String) row.get("content"), (String) row.get("section_path"));
    }
}

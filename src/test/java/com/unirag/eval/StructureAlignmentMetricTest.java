package com.unirag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 结构对齐指标单测（tasks.md T020）。 */
class StructureAlignmentMetricTest {

    private static final List<String> HEADINGS = List.of("一、总体要求", "（一）基本原则", "二、重点任务");

    @Test
    void alignedChunksHaveZeroCrossingRate() {
        List<StructureAlignmentMetrics.ChunkView> chunks = List.of(
                new StructureAlignmentMetrics.ChunkView("坚持基本原则的具体内容说明。", "一、总体要求/（一）基本原则"),
                new StructureAlignmentMetrics.ChunkView("重点任务的具体内容说明。", "二、重点任务"));
        StructureAlignmentMetrics metrics = StructureAlignmentMetrics.compute(HEADINGS, chunks);
        assertThat(metrics.boundaryCrossingRate()).isZero();
        assertThat(metrics.sectionPathCoverage()).isEqualTo(1.0);
    }

    @Test
    void chunkContainingHeadingLineCountsAsCrossing() {
        List<StructureAlignmentMetrics.ChunkView> chunks = List.of(
                new StructureAlignmentMetrics.ChunkView("一、总体要求\n正文内容延续。", ""),
                new StructureAlignmentMetrics.ChunkView("普通正文。", null));
        StructureAlignmentMetrics metrics = StructureAlignmentMetrics.compute(HEADINGS, chunks);
        assertThat(metrics.boundaryCrossingRate()).isEqualTo(0.5);
        // 前言空串路径计为已覆盖（携带了 section_path），NULL 才是不覆盖
        assertThat(metrics.sectionPathCoverage()).isEqualTo(0.5);
    }

    @Test
    void emptyChunkListYieldsZeroMetrics() {
        StructureAlignmentMetrics metrics = StructureAlignmentMetrics.compute(HEADINGS, List.of());
        assertThat(metrics.boundaryCrossingRate()).isZero();
        assertThat(metrics.sectionPathCoverage()).isZero();
    }
}

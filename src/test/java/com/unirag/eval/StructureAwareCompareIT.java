package com.unirag.eval;

import com.unirag.eval.report.EvaluationReport;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 验收（spec.md US3，FR-010，SC-001/002）：同一多级标题语料分别经递归基线与
 * 结构感知策略真实摄取，结构感知组的跨章节边界率 = 0、章节路径覆盖率 = 100%，
 * 双报告 SUCCESS 且语料/查询哈希一致可对比。
 * 确定性嵌入替身（8 维，宪法 III）；报告写临时目录。
 */
@Tag("eval")
class StructureAwareCompareIT extends AbstractEvaluationIT {

    private static final String RECURSIVE_DATASET = "sa-compare-recursive";
    private static final String STRUCTURE_DATASET = "sa-compare-structure";

    private static final List<String> HEADINGS = List.of(
            "一、总体要求", "（一）基本原则", "（二）发展目标",
            "二、重点任务", "（一）智能排产赋能", "（二）质量检测升级", "（三）供应链协同");

    @TempDir
    Path reportDir;

    @Test
    void structureAwareGroupHasZeroCrossingAndFullCoverage() {
        EmbeddingModel model = new DeterministicEmbeddingModel(8);

        Path recursiveReport = runner().run(options(RECURSIVE_DATASET), model);
        StructureAlignmentMetrics recursiveMetrics = currentAlignmentMetrics();
        Path structureReport = runner().run(options(STRUCTURE_DATASET), model);
        StructureAlignmentMetrics structureMetrics = currentAlignmentMetrics();

        EvaluationReport recursive = readReport(recursiveReport);
        EvaluationReport structure = readReport(structureReport);
        assertThat(recursive.run().status()).isEqualTo(EvaluationReport.RunStatus.SUCCESS);
        assertThat(structure.run().status()).isEqualTo(EvaluationReport.RunStatus.SUCCESS);
        assertThat(structure.dataset().corpusHash()).isEqualTo(recursive.dataset().corpusHash());
        assertThat(structure.dataset().queriesHash()).isEqualTo(recursive.dataset().queriesHash());

        // SC-001/002：结构感知组边界率 0、覆盖率 100%；基线组无章节数据（覆盖率 0）形成对照
        assertThat(structureMetrics.boundaryCrossingRate()).isZero();
        assertThat(structureMetrics.sectionPathCoverage()).isEqualTo(1.0);
        assertThat(recursiveMetrics.sectionPathCoverage()).isZero();
    }

    /** 当前评测租户在库全部 chunk（PARENT+CHILD）的结构对齐指标；每次 run 后立即采样（复用同租户会清库）。 */
    private StructureAlignmentMetrics currentAlignmentMetrics() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT dc.content, dc.section_path FROM document_chunk dc "
                        + "JOIN document d ON d.id = dc.document_id "
                        + "JOIN tenant t ON t.id = d.tenant_id "
                        + "WHERE t.tenant_code = 'eval_sa_compare'");
        List<StructureAlignmentMetrics.ChunkView> chunks = rows.stream()
                .map(StructureAlignmentMetrics::view).toList();
        return StructureAlignmentMetrics.compute(HEADINGS, chunks);
    }

    private EvaluationOptions options(String dataset) {
        return new EvaluationOptions(dataset, RunMode.REPLAY, null,
                reportDir, null, false, null, null, null, null);
    }

    private EvaluationReport readReport(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), EvaluationReport.class);
        } catch (Exception e) {
            throw new IllegalStateException("读取评测报告失败: " + path, e);
        }
    }
}

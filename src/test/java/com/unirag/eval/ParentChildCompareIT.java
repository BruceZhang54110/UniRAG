package com.unirag.eval;

import com.unirag.eval.report.EvaluationReport;
import com.unirag.eval.report.ReportComparator;
import com.unirag.eval.report.ReportWriter;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 验收（spec.md US3，FR-011）：同语料同查询下，单级 500 基线与父子（48/384）策略
 * 各跑一次真实管线摄取与检索评测，产出两份 SUCCESS 报告并经 ReportComparator 出对比报告。
 *
 * <p>确定性嵌入替身（8 维，宪法 III）；报告写临时目录，不污染 eval/reports。
 * SC-001 的验收口径（召回提升 ≥10% 或答案质量提升且召回不劣）在真实语料实跑后锁定
 * （research.md R8），本 IT 只断言"可对比、口径一致、父子数据确实落库"。
 */
@Tag("eval")
class ParentChildCompareIT extends AbstractEvaluationIT {

    private static final String SINGLE_DATASET = "pc-compare-single";
    private static final String PARENT_DATASET = "pc-compare-parent";

    @TempDir
    Path reportDir;

    @Test
    void comparesSingleLevelBaselineAgainstParentChild() {
        EmbeddingModel model = new DeterministicEmbeddingModel(8);

        Path singleReport = runner().run(options(SINGLE_DATASET), model);
        Path parentReport = runner().run(options(PARENT_DATASET), model);

        EvaluationReport single = readReport(singleReport);
        EvaluationReport parent = readReport(parentReport);
        assertThat(single.run().status()).isEqualTo(EvaluationReport.RunStatus.SUCCESS);
        assertThat(parent.run().status()).isEqualTo(EvaluationReport.RunStatus.SUCCESS);
        assertThat(single.dataset().documentCount()).isEqualTo(2);
        assertThat(parent.dataset().documentCount()).isEqualTo(2);
        assertThat(single.dataset().queryCount()).isEqualTo(6);
        assertThat(parent.dataset().queryCount()).isEqualTo(6);
        // 同语料同查询：快照哈希一致，对比口径可比
        assertThat(parent.dataset().corpusHash()).isEqualTo(single.dataset().corpusHash());
        assertThat(parent.dataset().queriesHash()).isEqualTo(single.dataset().queriesHash());
        // 指标口径一致（同 metrics.k）
        assertThat(parent.overall().keySet()).isEqualTo(single.overall().keySet());

        // 父子策略摄取确实产出两级数据（provisioner 按 strategy/parentMaxTokens 播种）
        Long parentRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk dc JOIN document d ON d.id = dc.document_id "
                        + "JOIN tenant t ON t.id = d.tenant_id "
                        + "WHERE t.tenant_code = 'eval_pc_compare' AND dc.chunk_level = 'PARENT'",
                Long.class);
        Long childRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk dc JOIN document d ON d.id = dc.document_id "
                        + "JOIN tenant t ON t.id = d.tenant_id "
                        + "WHERE t.tenant_code = 'eval_pc_compare' AND dc.chunk_level = 'CHILD'",
                Long.class);
        assertThat(parentRows).isPositive();
        assertThat(childRows).isGreaterThan(parentRows);

        // 对比报告产出（SC-001 数据载体；提升判定留待真实语料锁定口径）
        ReportComparator.ComparisonResult comparison =
                new ReportComparator(objectMapper).compare(singleReport, parentReport);
        Path comparisonPath = new ReportWriter(objectMapper).writeComparison(comparison, reportDir);
        assertThat(comparisonPath).exists();
    }

    private EvaluationOptions options(String dataset) {
        // REPLAY 模式 + 覆盖确定性模型：跳过外部凭据（宪法 III），模型解析走 override 短路
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

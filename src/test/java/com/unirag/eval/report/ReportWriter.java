package com.unirag.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** 报告写出：JSON 供比较，Markdown 供人工阅读（013 contracts/report.md §1/§6）。 */
public final class ReportWriter {

    private final ObjectMapper mapper;

    public ReportWriter(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path write(EvaluationReport report, Path reportDir) {
        Path json = reportDir.resolve(report.run().id() + ".json");
        Path markdown = reportDir.resolve(report.run().id() + ".md");
        writeAtomic(json, toBytes(report));
        writeAtomic(markdown, markdown(report).getBytes(StandardCharsets.UTF_8));
        return json;
    }

    public Path writeComparison(ReportComparator.ComparisonResult comparison, Path reportDir) {
        String runId = comparison.candidate() == null || comparison.candidate().runId() == null
                ? "comparison" : comparison.candidate().runId();
        Path json = reportDir.resolve(runId + "-comparison.json");
        Path markdown = reportDir.resolve(runId + "-comparison.md");
        writeAtomic(json, toBytes(comparison));
        writeAtomic(markdown, comparisonMarkdown(comparison).getBytes(StandardCharsets.UTF_8));
        return json;
    }

    private byte[] toBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new IllegalStateException("序列化报告失败", e);
        }
    }

    private static void writeAtomic(Path path, byte[] bytes) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temp, bytes);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("写入报告失败: " + path.toAbsolutePath(), e);
        }
    }

    private String markdown(EvaluationReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# RAG 检索评测报告\n\n");
        out.append("## 运行信息\n\n");
        out.append("| 项 | 值 |\n|---|---|\n");
        row(out, "运行 ID", report.run().id());
        row(out, "模式", report.run().mode().name().toLowerCase());
        row(out, "状态", report.run().status().name());
        row(out, "开始", report.run().startedAt());
        row(out, "结束", report.run().finishedAt());
        row(out, "数据集", report.dataset().name() + " @ " + report.dataset().version());
        row(out, "模型", report.config().embedding().model()
                + " (" + report.config().embedding().dimension() + " 维)");
        row(out, "检索配置", "topK=" + report.config().retrieval().topK()
                + ", recallTopK=" + report.config().retrieval().recallTopK()
                + ", minScore=" + report.config().retrieval().minScore()
                + ", rerank=" + report.config().retrieval().rerank()
                + ", rewrite=" + report.config().retrieval().rewrite());
        if (report.run().failureReason() != null) {
            row(out, "失败原因", report.run().failureReason());
        }
        out.append('\n');

        out.append("## 总体指标\n\n");
        out.append("| 指标 | 值 |\n|---|---|\n");
        report.overall().forEach((name, value) -> row(out, name, format(value)));
        out.append('\n');

        out.append("## 分维度指标\n\n");
        report.byTag().forEach((tag, metrics) -> {
            out.append("### ").append(tag).append("\n\n");
            out.append("| 指标 | 值 |\n|---|---|\n");
            metrics.forEach((name, value) -> row(out, name, format(value)));
            out.append('\n');
        });

        out.append("## 失败归因\n\n");
        out.append("| 归因 | 数量 |\n|---|---|\n");
        report.failures().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> row(out, entry.getKey(), String.valueOf(entry.getValue())));
        out.append('\n');

        out.append("## 失败查询明细\n\n");
        List<EvaluationReport.QueryResult> failures = report.queries().stream()
                .filter(query -> query.failure() != null)
                .toList();
        if (failures.isEmpty()) {
            out.append("无失败查询。\n\n");
        } else {
            for (EvaluationReport.QueryResult query : failures) {
                out.append("### ").append(query.id()).append(' ').append(query.question()).append("\n\n");
                out.append("- 标签：").append(String.join(", ", query.tags())).append('\n');
                out.append("- 归因：").append(query.failure()).append('\n');
                out.append("- top-k：\n");
                for (EvaluationReport.RetrievedItem item : query.retrieved()) {
                    out.append("  - #").append(item.rank()).append(' ')
                            .append(item.doc()).append('#').append(item.chunkIndex())
                            .append(" score=").append(format(item.score()))
                            .append(" docRelevant=").append(item.documentRelevant())
                            .append(" chunkRelevant=").append(item.chunkRelevant())
                            .append('\n');
                }
                if (!query.evidence().isEmpty()) {
                    out.append("- 证据：\n");
                    query.evidence().forEach(evidence -> out.append("  - ").append(evidence).append('\n'));
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private String comparisonMarkdown(ReportComparator.ComparisonResult comparison) {
        StringBuilder out = new StringBuilder();
        out.append("# RAG 评测报告比较\n\n");
        out.append("| 项 | 值 |\n|---|---|\n");
        row(out, "可比", String.valueOf(comparison.comparable()));
        row(out, "基线", comparison.baseline() == null ? "-" : comparison.baseline().runId());
        row(out, "候选", comparison.candidate() == null ? "-" : comparison.candidate().runId());
        row(out, "退化项", String.valueOf(comparison.summary().regressed()));
        row(out, "阈值未通过", String.valueOf(comparison.summary().thresholdFailed()));
        out.append('\n');

        if (!comparison.differences().isEmpty()) {
            out.append("## 不可比字段\n\n");
            out.append("| 字段 | 基线 | 候选 |\n|---|---|---|\n");
            for (ReportComparator.Difference difference : comparison.differences()) {
                out.append("| ").append(difference.field()).append(" | ")
                        .append(escape(difference.baseline())).append(" | ")
                        .append(escape(difference.candidate())).append(" |\n");
            }
            out.append('\n');
            return out.toString();
        }

        out.append("## 指标变化\n\n");
        out.append("| 指标 | 基线 | 候选 | 变化 | 相对变化 | 阈值 | 结论 |\n");
        out.append("|---|---|---|---|---|---|---|\n");
        for (ReportComparator.MetricDelta delta : comparison.metrics()) {
            out.append("| ").append(delta.name()).append(" | ")
                    .append(formatNullable(delta.baseline())).append(" | ")
                    .append(formatNullable(delta.candidate())).append(" | ")
                    .append(formatSigned(delta.absoluteDelta())).append(" | ")
                    .append(formatRelative(delta.relativeDelta())).append(" | ")
                    .append(threshold(delta)).append(" | ")
                    .append(verdict(delta)).append(" |\n");
        }
        out.append('\n');

        if (!comparison.byTag().isEmpty()) {
            out.append("## 分维度变化\n\n");
            for (ReportComparator.TagDelta tag : comparison.byTag()) {
                out.append("### ").append(tag.tag()).append("\n\n");
                out.append("| 指标 | 基线 | 候选 | 变化 | 结论 |\n");
                out.append("|---|---|---|---|---|\n");
                for (ReportComparator.MetricDelta delta : tag.metrics()) {
                    out.append("| ").append(delta.name()).append(" | ")
                            .append(formatNullable(delta.baseline())).append(" | ")
                            .append(formatNullable(delta.candidate())).append(" | ")
                            .append(formatSigned(delta.absoluteDelta())).append(" | ")
                            .append(delta.regressed() ? "退化" : "持平/改善").append(" |\n");
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String threshold(ReportComparator.MetricDelta delta) {
        if (delta.threshold() == null) {
            return "-";
        }
        if (delta.threshold().min() != null) {
            return ">= " + format(delta.threshold().min());
        }
        return "<= " + format(delta.threshold().max());
    }

    private static String verdict(ReportComparator.MetricDelta delta) {
        if (Boolean.FALSE.equals(delta.passed())) {
            return "阈值未通过";
        }
        if (delta.regressed()) {
            return "退化";
        }
        if (Boolean.TRUE.equals(delta.passed())) {
            return "通过";
        }
        return "记录";
    }

    private static void row(StringBuilder out, String key, String value) {
        out.append("| ").append(escape(key)).append(" | ").append(escape(value)).append(" |\n");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String format(double value) {
        return String.format("%.4f", value);
    }

    private static String formatNullable(Double value) {
        return value == null ? "-" : format(value);
    }

    private static String formatSigned(Double value) {
        if (value == null) {
            return "-";
        }
        return (value >= 0 ? "+" : "") + format(value);
    }

    private static String formatRelative(Double value) {
        if (value == null) {
            return "-";
        }
        return (value >= 0 ? "+" : "") + String.format("%.2f%%", value * 100);
    }

}

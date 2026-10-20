package com.unirag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.eval.report.ReportComparator;
import com.unirag.eval.report.ReportWriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 报告比较薄启动器（013 tasks T031）。 */
@Tag("eval")
class ReportCompareIT {

    @Test
    void compareReports() {
        Path baseline = path("eval.baseline");
        Path candidate = path("eval.candidate");
        Path reportDir = pathOr("eval.reportDir", EvaluationOptions.DEFAULT_REPORT_DIR);
        assertNotNull(baseline, "eval.baseline 必填");
        assertNotNull(candidate, "eval.candidate 必填");

        ReportComparator comparator = new ReportComparator(new ObjectMapper());
        ReportComparator.ComparisonResult result = comparator.compare(baseline, candidate);
        Path output = new ReportWriter(new ObjectMapper()).writeComparison(result, reportDir);
        System.out.println("Comparison report: " + output.toAbsolutePath());

        boolean failOnThreshold = Boolean.parseBoolean(System.getProperty("eval.failOnThreshold", "false"));
        if (failOnThreshold && result.summary().thresholdFailed() > 0) {
            throw new AssertionError("阈值未通过: " + result.summary().thresholdFailed() + " 项");
        }
    }

    private static Path path(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static Path pathOr(String name, String fallback) {
        Path value = path(name);
        return value == null ? Path.of(fallback) : value;
    }
}

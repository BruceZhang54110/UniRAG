package com.unirag.eval.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.eval.dataset.ThresholdRule;
import com.unirag.eval.metric.MetricKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 报告比较与阈值判定（013 contracts/report.md §4/§5）。 */
public final class ReportComparator {

    private final ObjectMapper mapper;

    public ReportComparator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ComparisonResult compare(Path baselinePath, Path candidatePath) {
        JsonNode baseline = read(baselinePath);
        JsonNode candidate = read(candidatePath);

        List<Difference> differences = new ArrayList<>();
        compareField(differences, "dataset.version", baseline, candidate);
        compareMode(differences, baseline, candidate);
        compareField(differences, "config.embedding.model", baseline, candidate);
        compareField(differences, "config.embedding.dimension", baseline, candidate);
        compareField(differences, "config.pipelines", baseline, candidate);
        compareField(differences, "config.retrieval", baseline, candidate);
        compareField(differences, "config.metrics", baseline, candidate);

        RunRef baselineRef = new RunRef(text(baseline, "run.id"), text(baseline, "dataset.version"));
        RunRef candidateRef = new RunRef(text(candidate, "run.id"), text(candidate, "dataset.version"));
        if (!differences.isEmpty()) {
            return new ComparisonResult(1, false, baselineRef, candidateRef, differences,
                    List.of(), List.of(),
                    new Summary(0, 0, differences.stream().map(Difference::field).toList()));
        }

        JsonNode thresholds = candidate.path("config").path("thresholds");
        List<MetricDelta> metrics = deltas(baseline.path("overall"), candidate.path("overall"), thresholds);
        List<TagDelta> byTag = tagDeltas(baseline.path("byTag"), candidate.path("byTag"));

        int regressed = (int) (metrics.stream().filter(MetricDelta::regressed).count()
                + byTag.stream().flatMap(tag -> tag.metrics().stream())
                .filter(MetricDelta::regressed).count());
        int thresholdFailed = (int) metrics.stream()
                .filter(delta -> Boolean.FALSE.equals(delta.passed())).count();
        return new ComparisonResult(1, true, baselineRef, candidateRef, List.of(),
                metrics, byTag, new Summary(regressed, thresholdFailed, List.of()));
    }

    private List<MetricDelta> deltas(JsonNode baseline, JsonNode candidate, JsonNode thresholds) {
        List<MetricDelta> result = new ArrayList<>();
        List<String> names = new ArrayList<>();
        candidate.fieldNames().forEachRemaining(names::add);
        names.sort(Comparator.naturalOrder());
        for (String name : names) {
            Double baselineValue = value(baseline, name);
            Double candidateValue = value(candidate, name);
            if (candidateValue == null) {
                continue;
            }
            result.add(delta(name, baselineValue, candidateValue, threshold(thresholds, name)));
        }
        return result;
    }

    private List<TagDelta> tagDeltas(JsonNode baseline, JsonNode candidate) {
        List<TagDelta> result = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        candidate.fieldNames().forEachRemaining(tags::add);
        tags.sort(Comparator.naturalOrder());
        for (String tag : tags) {
            JsonNode baselineMetrics = baseline.path(tag);
            JsonNode candidateMetrics = candidate.path(tag);
            List<MetricDelta> deltas = new ArrayList<>();
            List<String> names = new ArrayList<>();
            candidateMetrics.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                Double baselineValue = value(baselineMetrics, name);
                Double candidateValue = value(candidateMetrics, name);
                if (candidateValue != null) {
                    deltas.add(delta(name, baselineValue, candidateValue, null));
                }
            }
            result.add(new TagDelta(tag, deltas));
        }
        return result;
    }

    private MetricDelta delta(String name, Double baseline, Double candidate,
                              ThresholdRule thresholdRule) {
        Double absolute = baseline == null ? null : candidate - baseline;
        Double relative = baseline == null || baseline == 0.0 ? null : absolute / baseline;
        Boolean passed = thresholdRule == null ? null : thresholdPassed(name, candidate, thresholdRule);
        boolean regressed = absolute != null && absolute != 0.0
                && (MetricKeys.higherIsBetter(name) ? absolute < 0 : absolute > 0);
        return new MetricDelta(name, baseline, candidate, absolute, relative, thresholdRule, passed, regressed);
    }

    private static boolean thresholdPassed(String name, double value, ThresholdRule rule) {
        if (rule.min() != null) {
            return value >= rule.min();
        }
        if (rule.max() != null) {
            return value <= rule.max();
        }
        return true;
    }

    private static ThresholdRule threshold(JsonNode thresholds, String name) {
        JsonNode node = thresholds.path(name);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        Double min = node.hasNonNull("min") ? node.get("min").asDouble() : null;
        Double max = node.hasNonNull("max") ? node.get("max").asDouble() : null;
        return new ThresholdRule(min, max);
    }

    private static Double value(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static void compareField(List<Difference> differences, String field,
                                     JsonNode baseline, JsonNode candidate) {
        JsonNode baselineNode = at(baseline, field);
        JsonNode candidateNode = at(candidate, field);
        if (!Objects.equals(baselineNode, candidateNode)) {
            differences.add(new Difference(field, render(baselineNode), render(candidateNode)));
        }
    }

    private static void compareMode(List<Difference> differences, JsonNode baseline, JsonNode candidate) {
        String baselineMode = normalizeMode(text(baseline, "run.mode"));
        String candidateMode = normalizeMode(text(candidate, "run.mode"));
        if (!Objects.equals(baselineMode, candidateMode)) {
            differences.add(new Difference("run.mode", baselineMode, candidateMode));
        }
    }

    private static String normalizeMode(String mode) {
        return "record".equals(mode) ? "live" : mode;
    }

    private static JsonNode at(JsonNode root, String path) {
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            current = current.path(part);
        }
        return current;
    }

    private static String text(JsonNode root, String path) {
        JsonNode node = at(root, path);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static String render(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "<missing>";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private JsonNode read(Path path) {
        try {
            return mapper.readTree(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException("读取报告失败: " + path.toAbsolutePath(), e);
        }
    }

    public record ComparisonResult(int comparisonVersion,
                                   boolean comparable,
                                   RunRef baseline,
                                   RunRef candidate,
                                   List<Difference> differences,
                                   List<MetricDelta> metrics,
                                   List<TagDelta> byTag,
                                   Summary summary) {
    }

    public record RunRef(String runId, String datasetVersion) {
    }

    public record Difference(String field, String baseline, String candidate) {
    }

    public record MetricDelta(String name,
                              Double baseline,
                              Double candidate,
                              Double absoluteDelta,
                              Double relativeDelta,
                              ThresholdRule threshold,
                              Boolean passed,
                              boolean regressed) {
    }

    public record TagDelta(String tag, List<MetricDelta> metrics) {
    }

    public record Summary(int regressed, int thresholdFailed, List<String> incomparableFields) {
    }
}

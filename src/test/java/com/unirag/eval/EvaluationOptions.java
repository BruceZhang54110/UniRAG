package com.unirag.eval;

import java.nio.file.Path;

/**
 * 评测运行参数（013 contracts/run-cli.md §2）。
 *
 * <p>模型凭据不进入本对象：live/record 可直接复用数据库中已有租户的 EMBEDDING_MODEL 资源，
 * 也可回退到 {@code EVAL_EMBEDDING_*} 环境变量；两种方式都不会把密钥写入报告。
 */
public record EvaluationOptions(String dataset,
                                RunMode mode,
                                Path datasetDir,
                                Path reportDir,
                                Path baselineDir,
                                boolean failOnThreshold,
                                Path compareTo,
                                Path baseline,
                                Path candidate,
                                Path fixture,
                                String embeddingTenant,
                                String embeddingModelKey) {

    public static final String DEFAULT_REPORT_DIR = "eval/reports";

    public static final String DEFAULT_BASELINE_DIR = "eval/baselines";

    public EvaluationOptions {
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException("eval.dataset 必填");
        }
        datasetDir = datasetDir == null ? Path.of("eval/datasets", dataset) : datasetDir;
        reportDir = reportDir == null ? Path.of(DEFAULT_REPORT_DIR) : reportDir;
        baselineDir = baselineDir == null ? Path.of(DEFAULT_BASELINE_DIR) : baselineDir;
        fixture = fixture == null ? Path.of("eval/fixtures", dataset, "embeddings.json") : fixture;
    }

    /** 兼容既有调用方：不指定数据库中的源模型租户。 */
    public EvaluationOptions(String dataset,
                             RunMode mode,
                             Path datasetDir,
                             Path reportDir,
                             Path baselineDir,
                             boolean failOnThreshold,
                             Path compareTo,
                             Path baseline,
                             Path candidate,
                             Path fixture) {
        this(dataset, mode, datasetDir, reportDir, baselineDir, failOnThreshold,
                compareTo, baseline, candidate, fixture, null, null);
    }

    public boolean usesDatabaseEmbeddingModel() {
        return embeddingTenant != null && !embeddingTenant.isBlank();
    }

    public static EvaluationOptions fromSystemProperties() {
        String dataset = System.getProperty("eval.dataset");
        RunMode mode = RunMode.parse(System.getProperty("eval.mode"));
        return new EvaluationOptions(
                dataset,
                mode,
                path(System.getProperty("eval.datasetDir")),
                path(System.getProperty("eval.reportDir")),
                path(System.getProperty("eval.baselineDir")),
                Boolean.parseBoolean(System.getProperty("eval.failOnThreshold", "false")),
                path(System.getProperty("eval.compareTo")),
                path(System.getProperty("eval.baseline")),
                path(System.getProperty("eval.candidate")),
                path(System.getProperty("eval.fixture")),
                textOrEnv("eval.embeddingTenant", "EVAL_EMBEDDING_TENANT"),
                textOrEnv("eval.embeddingModelKey", "EVAL_EMBEDDING_MODEL_KEY"));
    }

    private static String textOrEnv(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static Path path(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}

package com.unirag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.eval.dataset.DatasetConfig;
import com.unirag.eval.dataset.DatasetLoader;
import com.unirag.eval.dataset.DatasetValidationException;
import com.unirag.eval.dataset.DatasetValidator;
import com.unirag.eval.dataset.EvaluationDataset;
import com.unirag.eval.dataset.PipelineConfig;
import com.unirag.eval.dataset.QueryCase;
import com.unirag.eval.embedding.RecordingEmbeddingModel;
import com.unirag.eval.embedding.RecordingScoringModel;
import com.unirag.eval.embedding.ReplayEmbeddingModel;
import com.unirag.eval.embedding.ReplayScoringModel;
import com.unirag.eval.metric.Attribution;
import com.unirag.eval.metric.FailureAttributor;
import com.unirag.eval.metric.FailureCategory;
import com.unirag.eval.metric.NormalizedText;
import com.unirag.eval.metric.QueryOutcome;
import com.unirag.eval.metric.RelevanceMatcher;
import com.unirag.eval.metric.RetrievalMetrics;
import com.unirag.eval.report.EvaluationReport;
import com.unirag.eval.report.ReportComparator;
import com.unirag.eval.report.ReportWriter;
import com.unirag.eval.retrieval.EvaluationRetriever;
import com.unirag.eval.setup.EvalTenantProvisioner;
import com.unirag.eval.setup.EvalTenantResourceRouter;
import com.unirag.eval.setup.EvaluationIndexPreparer;
import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.model.FailedTable;
import com.unirag.retrieval.model.RetrievedChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 评测编排：校验 → 准备 → 摄取 → 检索 → 指标 → 归因 → 报告（013 plan Implementation Outline）。 */
public final class EvaluationRunner {

    private static final DateTimeFormatter RUN_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final DatasetLoader loader;

    private final DatasetValidator validator;

    private final EvalTenantProvisioner provisioner;

    private final EvaluationIndexPreparer indexPreparer;

    private final EvaluationRetriever retriever;

    private final RetrievalMetrics metrics;

    private final FailureAttributor attributor;

    private final ReportWriter writer;

    private final EvalTenantResourceRouter router;

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper;

    private final RelevanceMatcher matcher = new RelevanceMatcher();

    private String resolvedModelName = "unknown";

    public EvaluationRunner(DatasetLoader loader,
                            DatasetValidator validator,
                            EvalTenantProvisioner provisioner,
                            EvaluationIndexPreparer indexPreparer,
                            EvaluationRetriever retriever,
                            RetrievalMetrics metrics,
                            FailureAttributor attributor,
                            ReportWriter writer,
                            EvalTenantResourceRouter router,
                            JdbcTemplate jdbcTemplate,
                            ObjectMapper mapper) {
        this.loader = loader;
        this.validator = validator;
        this.provisioner = provisioner;
        this.indexPreparer = indexPreparer;
        this.retriever = retriever;
        this.metrics = metrics;
        this.attributor = attributor;
        this.writer = writer;
        this.router = router;
        this.jdbcTemplate = jdbcTemplate;
        this.mapper = mapper;
    }

    public Path run(EvaluationOptions options) {
        return run(options, null);
    }

    /** 供测试注入确定性模型；生产路径不传 override。 */
    public Path run(EvaluationOptions options, EmbeddingModel override) {
        Instant startedAt = Instant.now();
        EvaluationDataset dataset = null;
        String runId = null;
        try {
            dataset = loader.load(options.datasetDir());
            validator.validate(dataset, options.dataset());
            runId = uniqueRunId(options.reportDir(), codeVersion());

            long tenantId = provisioner.provision(dataset, options.mode(),
                    options.usesDatabaseEmbeddingModel());
            Map<String, EmbeddingModel> models = resolveModels(dataset, options, override);
            validateDimensions(dataset, models, options.mode(), override);
            resolvedModelName = models.values().stream().findFirst()
                    .map(model -> model.modelName() == null ? "unknown" : model.modelName())
                    .orElse("unknown");

            ScoringModel rerankModel = resolveRerankModel(dataset, options);
            provisioner.wireRouter(router, dataset, models, rerankModel);

            indexPreparer.prepare(dataset);
            List<QueryOutcome> outcomes = evaluate(dataset, tenantId);

            EvaluationReport report = report(dataset, options.mode(), runId, startedAt, outcomes,
                    EvaluationReport.RunStatus.SUCCESS, null);
            Path reportPath = writer.write(report, options.reportDir());

            writeFixture(dataset, options, models, rerankModel);

            if (options.compareTo() != null) {
                compare(options, reportPath);
            }
            return reportPath;
        } catch (RuntimeException e) {
            if (dataset != null && runId != null) {
                writeFailedReport(dataset, options, runId, startedAt, e);
            }
            throw e;
        }
    }

    private List<QueryOutcome> evaluate(EvaluationDataset dataset, long tenantId) {
        List<QueryOutcome> outcomes = new ArrayList<>();
        List<FailureAttributor.ChunkView> allChunks = chunkViews(dataset.config().tenant());
        for (QueryCase query : dataset.queries()) {
            EvaluationRetriever.RetrievalOutcome retrieval = retriever.retrieve(dataset, tenantId, query);
            List<RetrievedChunk> production = retrieval.production().chunks();
            Map<String, Double> queryMetrics = metrics.perQuery(
                    query, production, dataset.config().metrics().k(), matcher);
            Attribution attribution = query.answerable()
                    ? attributor.attribute(query, production, retrieval.diagnosticTables(),
                    retrieval.diagnosticMerged(), allChunks, dataset.config().retrieval())
                    : attributor.attributeUnanswerable(query, production);
            outcomes.add(new QueryOutcome(query, production, retrieval.diagnosticTables(),
                    retrieval.diagnosticMerged(), queryMetrics, retrieval.latencyNanos(), attribution));
        }
        return outcomes;
    }

    private EvaluationReport report(EvaluationDataset dataset,
                                    RunMode mode,
                                    String runId,
                                    Instant startedAt,
                                    List<QueryOutcome> outcomes,
                                    EvaluationReport.RunStatus status,
                                    String failureReason) {
        Map<String, Integer> failures = new LinkedHashMap<>();
        for (FailureCategory category : FailureCategory.values()) {
            if (category != FailureCategory.UNANSWERABLE_FALSE_POSITIVE) {
                failures.put(category.name(), 0);
            }
        }
        for (QueryOutcome outcome : outcomes) {
            FailureCategory category = outcome.attribution().category();
            if (category != null && category != FailureCategory.UNANSWERABLE_FALSE_POSITIVE) {
                failures.merge(category.name(), 1, Integer::sum);
            }
        }

        long answerable = dataset.queries().stream().filter(QueryCase::answerable).count();
        return new EvaluationReport(
                1,
                new EvaluationReport.RunInfo(runId, mode, status,
                        startedAt.toString(), Instant.now().toString(), failureReason),
                new EvaluationReport.DatasetSnapshot(
                        dataset.name(), dataset.version(), dataset.corpusHash(), dataset.queriesHash(),
                        dataset.corpus().size(), dataset.queries().size(),
                        (int) answerable, dataset.queries().size() - (int) answerable),
                configSnapshot(dataset),
                metrics.aggregateOverall(outcomes, dataset.config().metrics()),
                metrics.aggregateByTag(outcomes, dataset.config().metrics()),
                queryResults(dataset, outcomes),
                failures);
    }

    private EvaluationReport.ConfigSnapshot configSnapshot(EvaluationDataset dataset) {
        DatasetConfig config = dataset.config();
        return new EvaluationReport.ConfigSnapshot(
                codeVersion(),
                new EvaluationReport.EmbeddingSnapshot(resolvedModelName, config.store().dimension()),
                config.pipelines().stream()
                        .map(pipeline -> new EvaluationReport.PipelineSnapshot(
                                pipeline.key(), pipeline.maxSegmentTokens(), pipeline.overlapTokens(),
                                pipeline.modelKey(), pipeline.storeKey()))
                        .toList(),
                new EvaluationReport.RetrievalSnapshot(
                        config.retrieval().topK(), config.retrieval().recallTopK(),
                        config.retrieval().minScore(), config.retrieval().rerank(),
                        config.retrieval().rewrite()),
                new EvaluationReport.MetricsSnapshot(config.metrics().k(), config.metrics().diagnosticTopK()),
                dataset.thresholds());
    }

    private List<EvaluationReport.QueryResult> queryResults(EvaluationDataset dataset,
                                                            List<QueryOutcome> outcomes) {
        List<EvaluationReport.QueryResult> results = new ArrayList<>();
        for (QueryOutcome outcome : outcomes) {
            QueryCase query = outcome.query();
            List<EvaluationReport.RetrievedItem> retrieved = new ArrayList<>();
            for (int i = 0; i < outcome.retrieved().size(); i++) {
                RetrievedChunk chunk = outcome.retrieved().get(i);
                retrieved.add(new EvaluationReport.RetrievedItem(
                        i + 1, chunk.docName(), chunk.chunkIndex(), chunk.pipelineKey(),
                        chunk.score(), chunk.rerankScore(),
                        matcher.documentMatches(chunk, query),
                        matcher.matchesAny(chunk, query)));
            }
            results.add(new EvaluationReport.QueryResult(
                    query.id(), query.question(), query.tags(), query.answerable(),
                    retrieved, diagnostic(outcome, query, dataset.config()),
                    outcome.metrics(),
                    outcome.attribution().category(),
                    outcome.attribution().evidence()));
        }
        return results;
    }

    private EvaluationReport.DiagnosticInfo diagnostic(QueryOutcome outcome, QueryCase query,
                                                       DatasetConfig config) {
        int searchedTables = outcome.diagnosticTables() == null
                ? 0 : outcome.diagnosticTables().searchedTables();
        List<String> failedTables = outcome.diagnosticTables() == null ? List.of()
                : outcome.diagnosticTables().failedTables().stream()
                .map(FailedTable::targetKey).toList();
        List<EvaluationReport.GoldCandidate> candidates = new ArrayList<>();
        for (var label : query.relevant()) {
            for (int i = 0; i < outcome.diagnosticMerged().size(); i++) {
                RetrievedChunk chunk = outcome.diagnosticMerged().get(i);
                if (chunk.docName() != null && chunk.docName().equals(label.doc())
                        && NormalizedText.contains(chunk.text(), label.span())) {
                    candidates.add(new EvaluationReport.GoldCandidate(
                            label.doc(), chunk.pipelineKey(), chunk.chunkIndex(), chunk.score(),
                            i + 1, chunk.score() < config.retrieval().minScore()));
                }
            }
        }
        return new EvaluationReport.DiagnosticInfo(searchedTables, failedTables, candidates);
    }

    private void compare(EvaluationOptions options, Path reportPath) {
        ReportComparator comparator = new ReportComparator(new ObjectMapper());
        ReportComparator.ComparisonResult result = comparator.compare(options.compareTo(), reportPath);
        writer.writeComparison(result, options.reportDir());
        if (options.failOnThreshold() && result.summary().thresholdFailed() > 0) {
            throw new IllegalStateException("阈值未通过: " + result.summary().thresholdFailed() + " 项");
        }
    }

    private Map<String, EmbeddingModel> resolveModels(EvaluationDataset dataset,
                                                      EvaluationOptions options,
                                                      EmbeddingModel override) {
        List<String> modelKeys = dataset.config().pipelines().stream()
                .map(PipelineConfig::modelKey).distinct().toList();
        if (modelKeys.size() > 1 && options.mode() != RunMode.LIVE) {
            throw new DatasetValidationException("V-009",
                    "record/replay 模式暂不支持多嵌入模型管线: " + modelKeys);
        }
        if (override != null) {
            Map<String, EmbeddingModel> models = new LinkedHashMap<>();
            modelKeys.forEach(key -> models.put(key, override));
            return models;
        }
        if (options.usesDatabaseEmbeddingModel()) {
            if (options.mode() == RunMode.REPLAY) {
                throw new DatasetValidationException("V-009",
                        "replay 模式不应指定 eval.embeddingTenant；请改用冻结夹具");
            }
            EmbeddingModel source = provisioner.resolveSourceEmbeddingModel(
                    options.embeddingTenant(), options.embeddingModelKey());
            EmbeddingModel resolved = options.mode() == RunMode.RECORD
                    ? new RecordingEmbeddingModel(source) : source;
            Map<String, EmbeddingModel> models = new LinkedHashMap<>();
            modelKeys.forEach(key -> models.put(key, resolved));
            return models;
        }
        return switch (options.mode()) {
            case REPLAY -> {
                ReplayEmbeddingModel replay;
                try {
                    replay = ReplayEmbeddingModel.load(options.fixture(), mapper);
                } catch (IOException e) {
                    throw new DatasetValidationException("V-009",
                            "加载 replay 夹具失败: " + options.fixture().toAbsolutePath()
                                    + " (" + e.getMessage() + ")");
                }
                Map<String, EmbeddingModel> models = new LinkedHashMap<>();
                modelKeys.forEach(key -> models.put(key, replay));
                yield models;
            }
            case LIVE -> provisioner.resolveEmbeddingModels(dataset);
            case RECORD -> {
                Map<String, EmbeddingModel> recorded = new LinkedHashMap<>();
                provisioner.resolveEmbeddingModels(dataset)
                        .forEach((key, model) -> recorded.put(key, new RecordingEmbeddingModel(model)));
                yield recorded;
            }
        };
    }

    private ScoringModel resolveRerankModel(EvaluationDataset dataset, EvaluationOptions options) {
        if (!dataset.config().retrieval().rerank()) {
            return null;
        }
        return switch (options.mode()) {
            case REPLAY -> {
                try {
                    yield ReplayScoringModel.load(rerankFixturePath(options), mapper);
                } catch (IOException e) {
                    throw new DatasetValidationException("V-009",
                            "加载重排 replay 夹具失败: " + rerankFixturePath(options).toAbsolutePath()
                                    + " (" + e.getMessage() + ")");
                }
            }
            case LIVE -> sourceRerankModel(options);
            case RECORD -> new RecordingScoringModel(sourceRerankModel(options));
        };
    }

    private ScoringModel sourceRerankModel(EvaluationOptions options) {
        if (!options.usesDatabaseEmbeddingModel()) {
            throw new DatasetValidationException("V-010",
                    "启用 rerank 时必须通过 eval.embeddingTenant 复用源租户的 RERANK_MODEL");
        }
        return provisioner.resolveSourceRerankModel(options.embeddingTenant());
    }

    private static Path rerankFixturePath(EvaluationOptions options) {
        return options.fixture().resolveSibling("rerank.json");
    }

    private void validateDimensions(EvaluationDataset dataset, Map<String, EmbeddingModel> models,
                                    RunMode mode, EmbeddingModel override) {
        int expected = dataset.config().store().dimension();
        for (Map.Entry<String, EmbeddingModel> entry : models.entrySet()) {
            int actual;
            if (mode == RunMode.REPLAY || override != null) {
                actual = entry.getValue().dimension();
            } else {
                // 真实模型的 dimension() 可能是默认值；以一次真实嵌入的实际长度为准。
                actual = entry.getValue().embed("dimension-probe").content().vector().length;
            }
            if (actual != expected) {
                throw new DatasetValidationException("V-009",
                        "模型 " + entry.getKey() + " 维度=" + actual
                                + " 与 store.dimension=" + expected + " 不一致");
            }
        }
    }

    private void writeFixture(EvaluationDataset dataset, EvaluationOptions options,
                              Map<String, EmbeddingModel> models, ScoringModel rerankModel) {
        if (options.mode() != RunMode.RECORD) {
            return;
        }
        for (EmbeddingModel model : models.values()) {
            if (model instanceof RecordingEmbeddingModel recording) {
                try {
                    recording.writeFixture(options.fixture(), model.modelName());
                } catch (IOException e) {
                    throw new IllegalStateException("写入 replay 夹具失败: "
                            + options.fixture().toAbsolutePath(), e);
                }
                break;
            }
        }
        if (rerankModel instanceof RecordingScoringModel recording) {
            try {
                recording.writeFixture(rerankFixturePath(options), "source-rerank-model");
            } catch (IOException e) {
                throw new IllegalStateException("写入重排 replay 夹具失败: "
                        + rerankFixturePath(options).toAbsolutePath(), e);
            }
        }
    }

    private List<FailureAttributor.ChunkView> chunkViews(String tenant) {
        return jdbcTemplate.query("""
                SELECT d.doc_name AS doc, dc.content AS text
                FROM document_chunk dc
                JOIN document d ON dc.document_id = d.id
                WHERE d.tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)""",
                (rs, rowNum) -> new FailureAttributor.ChunkView(
                        rs.getString("doc"), rs.getString("text")), tenant);
    }

    private void writeFailedReport(EvaluationDataset dataset, EvaluationOptions options,
                                   String runId, Instant startedAt, RuntimeException error) {
        String reason = redact(Redaction.describe(error));
        EvaluationReport failed = report(dataset, options.mode(), runId, startedAt,
                List.of(), EvaluationReport.RunStatus.FAILED, reason);
        writer.write(failed, options.reportDir());
    }

    private static String redact(String reason) {
        String apiKey = System.getenv(EvalTenantProvisioner.ENV_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return reason.replace(apiKey, "***");
        }
        return reason;
    }

    private static String codeVersion() {
        String version = System.getProperty("eval.codeVersion",
                System.getenv().getOrDefault("GIT_COMMIT", "unknown"));
        return version.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static String uniqueRunId(Path reportDir, String codeVersion) {
        String base = RUN_ID_TIME.format(Instant.now()) + "-" + codeVersion;
        String candidate = base;
        int suffix = 1;
        while (Files.exists(reportDir.resolve(candidate + ".json"))) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}

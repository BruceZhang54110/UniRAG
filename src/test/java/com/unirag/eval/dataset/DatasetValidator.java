package com.unirag.eval.dataset;

import com.unirag.eval.metric.MetricKeys;
import com.unirag.eval.metric.NormalizedText;
import com.unirag.retrieval.model.RetrievalConstraints;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 数据集校验 V-001–V-011（013 data-model §5）。 */
public final class DatasetValidator {

    private static final Set<String> SPLITS = Set.of("dev", "test");

    public void validate(EvaluationDataset dataset, String directoryName) {
        require(dataset != null, "V-001", "数据集为空");
        validateIdentity(dataset, directoryName);
        validateCorpus(dataset);
        validateQueries(dataset);
        validateRetrieval(dataset);
        validatePipelines(dataset);
        validateThresholds(dataset);
    }

    private void validateIdentity(EvaluationDataset dataset, String directoryName) {
        DatasetConfig config = dataset.config();
        require(config != null, "V-001", "config.json 缺少配置");
        require(dataset.name() != null && dataset.name().equals(directoryName), "V-001",
                "数据集名称与目录名不一致: config.name=" + dataset.name() + ", dir=" + directoryName);
        require(config.name() != null && config.name().equals(directoryName), "V-001",
                "config.name 与目录名不一致: " + config.name() + " != " + directoryName);
        require(config.tenant() != null && config.tenant().startsWith("eval_"), "V-007",
                "评测租户必须以 eval_ 开头: " + config.tenant());
        require(config.store() != null, "V-006", "缺少 store 配置");
        validateStore("store", config.store());
        if (!config.stores().isEmpty()) {
            config.stores().forEach((key, store) -> {
                require(key != null && !key.isBlank(), "V-008", "stores key 不能为空");
                validateStore("stores." + key, store);
                require(store.dimension() == config.store().dimension(), "V-006",
                        "所有 stores 维度必须一致: " + key + "=" + store.dimension()
                                + ", store=" + config.store().dimension());
            });
            config.pipelines().forEach(pipeline -> require(
                    config.stores().containsKey(pipeline.storeKey()), "V-008",
                    "管线 storeKey 未在 stores 中定义: " + pipeline.key() + " -> " + pipeline.storeKey()));
        }
    }

    private void validateStore(String label, StoreConfig store) {
        require(store != null, "V-006", "缺少 " + label + " 配置");
        require(store.table() != null && store.table().startsWith("embeddings_eval_"), "V-007",
                label + " 向量表必须以 embeddings_eval_ 开头: " + store.table());
        require(store.dimension() > 0, "V-006",
                label + ".dimension 必须大于 0: " + store.dimension());
    }

    private void validateCorpus(EvaluationDataset dataset) {
        require(!dataset.corpus().isEmpty(), "V-002", "语料为空，至少需要 1 篇文档");
        Set<String> names = new HashSet<>();
        for (CorpusDocument doc : dataset.corpus()) {
            require(doc.docName() != null && !doc.docName().isBlank(), "V-002", "文档缺少 docName");
            require(names.add(doc.docName()), "V-002", "文档标识重复: " + doc.docName());
            require(doc.content() != null && !doc.content().isBlank(), "V-002",
                    "文档正文为空: " + doc.docName());
        }
    }

    private void validateQueries(EvaluationDataset dataset) {
        require(!dataset.queries().isEmpty(), "V-003", "查询集为空，至少需要 1 条查询");
        Set<String> ids = new HashSet<>();
        Set<String> docs = new HashSet<>();
        dataset.corpus().forEach(doc -> docs.add(doc.docName()));

        for (QueryCase query : dataset.queries()) {
            require(query.id() != null && !query.id().isBlank(), "V-003", "查询缺少 id");
            require(ids.add(query.id()), "V-003", "查询标识重复: " + query.id());
            require(query.question() != null && !query.question().isBlank(), "V-003",
                    "查询问题为空: " + query.id());
            require(query.question().strip().length() <= RetrievalConstraints.MAX_TEXT_LENGTH, "V-003",
                    "查询超长: " + query.id());
            require(query.tags() != null && !query.tags().isEmpty(), "V-003",
                    "查询缺少标签: " + query.id());
            require(SPLITS.contains(query.split()), "V-003",
                    "查询 split 非法: " + query.id() + " -> " + query.split());

            if (!query.answerable()) {
                require(query.relevant().isEmpty(), "V-004",
                        "不可回答查询不得带标注: " + query.id());
                continue;
            }
            require(!query.relevant().isEmpty(), "V-004",
                    "可回答查询至少需要 1 条标注: " + query.id());
            for (RelevanceLabel label : query.relevant()) {
                require(label.doc() != null && docs.contains(label.doc()), "V-005",
                        "标注文档不存在: query=" + query.id() + ", doc=" + label.doc());
                require(label.span() != null && !label.span().isBlank(), "V-005",
                        "标注片段为空: query=" + query.id() + ", doc=" + label.doc());
                CorpusDocument doc = dataset.corpus().stream()
                        .filter(candidate -> candidate.docName().equals(label.doc()))
                        .findFirst().orElseThrow();
                require(NormalizedText.contains(doc.content(), label.span()), "V-005",
                        "标注片段在文档中找不到: query=" + query.id() + ", doc=" + label.doc()
                                + ", span=" + preview(label.span()));
            }
        }
    }

    private void validateRetrieval(EvaluationDataset dataset) {
        RetrievalConfig retrieval = dataset.config().retrieval();
        require(retrieval != null, "V-006", "缺少 retrieval 配置");
        require(retrieval.topK() >= 1 && retrieval.topK() <= RetrievalConstraints.MAX_TOP_K, "V-006",
                "topK 需在 1-" + RetrievalConstraints.MAX_TOP_K + ": " + retrieval.topK());
        require(retrieval.recallTopK() >= retrieval.topK()
                        && retrieval.recallTopK() <= RetrievalConstraints.MAX_TOP_K, "V-006",
                "recallTopK 需在 " + retrieval.topK() + "-" + RetrievalConstraints.MAX_TOP_K
                        + ": " + retrieval.recallTopK());
        require(retrieval.minScore() >= 0.0 && retrieval.minScore() <= 1.0, "V-006",
                "minScore 需在 0-1: " + retrieval.minScore());

        MetricConfig metrics = dataset.config().metrics();
        require(metrics != null && !metrics.k().isEmpty(), "V-006", "metrics.k 为空");
        require(metrics.diagnosticTopK() >= 1
                        && metrics.diagnosticTopK() <= RetrievalConstraints.MAX_TOP_K, "V-006",
                "diagnosticTopK 需在 1-" + RetrievalConstraints.MAX_TOP_K + ": " + metrics.diagnosticTopK());
        require(metrics.k().stream().allMatch(k -> k >= 1 && k <= RetrievalConstraints.MAX_TOP_K), "V-006",
                "metrics.k 每项需在 1-" + RetrievalConstraints.MAX_TOP_K + ": " + metrics.k());
        require(metrics.k().equals(metrics.k().stream().distinct().sorted().toList()), "V-006",
                "metrics.k 必须升序且不重复: " + metrics.k());
        require(metrics.k().contains(retrieval.topK()), "V-006",
                "metrics.k 必须包含 topK=" + retrieval.topK() + ": " + metrics.k());
    }

    private void validatePipelines(EvaluationDataset dataset) {
        List<PipelineConfig> pipelines = dataset.config().pipelines();
        require(pipelines != null && !pipelines.isEmpty(), "V-008", "至少需要 1 条摄取管线");
        Set<String> keys = new HashSet<>();
        for (PipelineConfig pipeline : pipelines) {
            require(pipeline.key() != null && !pipeline.key().isBlank(), "V-008", "管线缺少 key");
            require(keys.add(pipeline.key()), "V-008", "管线 key 重复: " + pipeline.key());
            require(pipeline.maxSegmentTokens() > 0, "V-008",
                    "maxSegmentTokens 必须大于 0: " + pipeline.key());
            require(pipeline.overlapTokens() >= 0
                            && pipeline.overlapTokens() < pipeline.maxSegmentTokens(), "V-008",
                    "overlapTokens 必须 >=0 且小于 maxSegmentTokens: " + pipeline.key());
            require(pipeline.modelKey() != null && !pipeline.modelKey().isBlank(), "V-008",
                    "管线缺少 modelKey: " + pipeline.key());
            require(pipeline.storeKey() != null && !pipeline.storeKey().isBlank(), "V-008",
                    "管线缺少 storeKey: " + pipeline.key());
        }
    }

    private void validateThresholds(EvaluationDataset dataset) {
        Set<String> supported = MetricKeys.supported(dataset.config().metrics());
        for (Map.Entry<String, ThresholdRule> entry : dataset.thresholds().entrySet()) {
            String metric = entry.getKey();
            ThresholdRule rule = entry.getValue();
            require(supported.contains(metric), "V-011", "不支持的阈值指标: " + metric);
            require(rule != null && (rule.min() != null ^ rule.max() != null), "V-011",
                    "阈值必须且只能配置 min 或 max: " + metric);
            double value = rule.min() != null ? rule.min() : rule.max();
            require(Double.isFinite(value) && value >= 0.0, "V-011",
                    "阈值必须为非负有限值: " + metric + "=" + value);
            require(MetricKeys.isLatency(metric) || value <= 1.0, "V-011",
                    "比例类阈值需在 0-1: " + metric + "=" + value);
        }
    }

    private static void require(boolean condition, String rule, String message) {
        if (!condition) {
            throw new DatasetValidationException(rule, message);
        }
    }

    private static String preview(String value) {
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }
}

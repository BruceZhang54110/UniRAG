package com.unirag.eval.dataset;

import java.util.List;
import java.util.Map;

/** 一次评测的完整输入（013 data-model §1.1）。 */
public record EvaluationDataset(String name,
                                List<CorpusDocument> corpus,
                                List<QueryCase> queries,
                                DatasetConfig config,
                                Map<String, ThresholdRule> thresholds,
                                String corpusHash,
                                String queriesHash,
                                String configHash,
                                String version) {

    public EvaluationDataset {
        corpus = corpus == null ? List.of() : List.copyOf(corpus);
        queries = queries == null ? List.of() : List.copyOf(queries);
        thresholds = thresholds == null ? Map.of() : Map.copyOf(thresholds);
    }
}

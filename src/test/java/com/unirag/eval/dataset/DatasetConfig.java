package com.unirag.eval.dataset;

import java.util.List;
import java.util.Map;

/**
 * 数据集运行配置（013 data-model §1.5 DatasetConfig）。
 *
 * <p>{@code stores} 可选：当多条管线使用不同向量表时，按 storeKey 指定各自的表与维度；
 * 省略时所有管线共用 {@code store}。
 */
public record DatasetConfig(String name,
                            String tenant,
                            StoreConfig store,
                            List<PipelineConfig> pipelines,
                            RetrievalConfig retrieval,
                            MetricConfig metrics,
                            Map<String, StoreConfig> stores) {

    public DatasetConfig {
        pipelines = pipelines == null ? List.of() : List.copyOf(pipelines);
        stores = stores == null ? Map.of() : Map.copyOf(stores);
    }

    /** 兼容既有配置：所有管线共用一个 store。 */
    public DatasetConfig(String name, String tenant, StoreConfig store, List<PipelineConfig> pipelines,
                         RetrievalConfig retrieval, MetricConfig metrics) {
        this(name, tenant, store, pipelines, retrieval, metrics, Map.of());
    }
}

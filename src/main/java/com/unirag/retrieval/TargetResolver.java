package com.unirag.retrieval;

import com.unirag.infrastructure.persistence.entity.IngestionPipeline;
import com.unirag.infrastructure.persistence.service.IngestionPipelineService;
import com.unirag.retrieval.model.TargetTable;
import com.unirag.common.web.ApiException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 解析本次检索的目标向量表（FR-003，contracts/retrieval-api.md §2）。
 *
 * <p>粒度可取摄取管线 key，也可取向量存储资源 key；省略则覆盖该租户全部已启用管线。
 * 指定了不存在的粒度时明确失败并列出可用粒度，MUST NOT 静默退化为全量检索。
 */
@Component
public class TargetResolver {

    private final IngestionPipelineService pipelineService;

    public TargetResolver(IngestionPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    public List<TargetTable> resolve(Long tenantId, String granularity) {
        List<IngestionPipeline> pipelines = pipelineService.findEnabledByTenant(tenantId);
        if (granularity == null) {
            return pipelines.stream().map(TargetResolver::toTarget).toList();
        }
        List<IngestionPipeline> matched = pipelines.stream()
                .filter(pipeline -> granularity.equals(pipeline.getPipelineKey())
                        || granularity.equals(pipeline.getEmbeddingStoreKey()))
                .toList();
        if (matched.isEmpty()) {
            throw new ApiException("GRANULARITY_NOT_FOUND",
                    "粒度不存在或对应管线未启用: " + granularity,
                    Map.of("available", pipelines.stream().map(IngestionPipeline::getPipelineKey).toList()));
        }
        return matched.stream().map(TargetResolver::toTarget).toList();
    }

    private static TargetTable toTarget(IngestionPipeline pipeline) {
        return new TargetTable(pipeline.getPipelineKey(), pipeline.getEmbeddingStoreKey(),
                pipeline.getEmbeddingModelKey());
    }
}

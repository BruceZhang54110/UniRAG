package com.unirag.retrieval.stage;

import com.unirag.infrastructure.persistence.entity.DocumentChunk;
import com.unirag.infrastructure.persistence.entity.IngestionPipeline;
import com.unirag.infrastructure.persistence.service.DocumentChunkService;
import com.unirag.infrastructure.persistence.service.IngestionPipelineService;
import com.unirag.retrieval.model.ParentFetchOutcome;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.observation.ObservationMetrics;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageStatus;
import dev.langchain4j.model.TokenCountEstimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 父子切片父块回填（020 FR-005/006/007/009，contracts/parent-child-chunking.md §2）：
 * 子块命中 → {@code parent_chunk_id} join 取回父块 → 按父块去重 → 管线预算截断 → 父块命中进 Prompt。
 *
 * <p>仅存在于问答执行链（MERGE 之后、PROMPT_ASSEMBLY 之前）；纯检索服务链不含此阶段。
 * 单级命中（无父块行）原样透传；父块行缺失降级为子块文本并记观测（FR-009）。
 * 预算按管线配置（tenant + pipelineKey）各自生效：父块数量与 token 双上限。
 */
@Slf4j
@Component
public class ParentFetchStage implements Stage<MergeResult, MergeResult> {

    private static final int DETAIL_MAX_LENGTH = 2000;

    private static final int DEFAULT_MAX_CHUNKS = 5;

    private static final int DEFAULT_MAX_TOKENS = 4000;

    private final DocumentChunkService chunkService;

    private final IngestionPipelineService pipelineService;

    private final TokenCountEstimator tokenizer;

    private final ObservationMetrics metrics;

    public ParentFetchStage(DocumentChunkService chunkService,
                            IngestionPipelineService pipelineService,
                            TokenCountEstimator tokenizer,
                            ObservationMetrics metrics) {
        this.chunkService = chunkService;
        this.pipelineService = pipelineService;
        this.tokenizer = tokenizer;
        this.metrics = metrics;
    }

    @Override
    public StageName name() {
        return StageName.PARENT_FETCH;
    }

    @Override
    public MergeResult execute(MergeResult merged, StageContext ctx) {
        long startedAt = System.nanoTime();
        List<RetrievedChunk> children = merged.chunks();
        if (children.isEmpty()) {
            ctx.put(ParentFetchOutcome.CONTEXT_KEY,
                    new ParentFetchOutcome(0, 0, 0, 0, elapsedMillis(startedAt),
                            StageStatus.SUCCESS, null, ""));
            return merged;
        }

        // 管线配置（回填预算）按 pipelineKey 索引；租户主键来自上下文（身份由调用方注入）
        Map<String, IngestionPipeline> pipelinesByKey = ctx.tenantId() == null
                ? Map.of()
                : pipelineService.findEnabledByTenant(ctx.tenantId()).stream()
                        .collect(Collectors.toMap(IngestionPipeline::getPipelineKey, Function.identity(),
                                (a, b) -> a, LinkedHashMap::new));

        // 按 (documentId, pipelineKey) 分组批量取父块（单条 join SQL / 组）
        Map<String, Map<Integer, DocumentChunk>> parentsByGroup = new LinkedHashMap<>();
        Map<String, List<RetrievedChunk>> childrenByGroup = children.stream()
                .collect(Collectors.groupingBy(
                        c -> c.documentId() + "|" + c.pipelineKey(), LinkedHashMap::new, Collectors.toList()));
        childrenByGroup.forEach((groupKey, groupChildren) -> {
            RetrievedChunk first = groupChildren.get(0);
            List<Integer> childSeqs = groupChildren.stream().map(RetrievedChunk::chunkIndex).distinct().toList();
            parentsByGroup.put(groupKey, chunkService.findParentsByChildSeqIndexes(
                    first.documentId(), pipelineIdOf(pipelinesByKey, first.pipelineKey()), childSeqs));
        });

        // 子块命中 → 父块命中：同父块只保留首条（入序即 rerank??score 降序，首条即最优代表）
        Map<Long, RetrievedChunk> parentHits = new LinkedHashMap<>();
        int degraded = 0;
        for (RetrievedChunk child : children) {
            String groupKey = child.documentId() + "|" + child.pipelineKey();
            DocumentChunk parent = parentsByGroup.getOrDefault(groupKey, Map.of()).get(child.chunkIndex());
            if (parent == null) {
                // 单级管线命中（无父块行）原样透传；父子管线父块缺失 → 降级并计数（FR-009）
                if (parentRowExpected(child, pipelinesByKey)) {
                    degraded++;
                }
                parentHits.putIfAbsent(-childSeqKey(child), child);
                continue;
            }
            parentHits.putIfAbsent(parent.getId(), new RetrievedChunk(child.documentId(), child.docName(),
                    child.pipelineKey(), child.documentVersionId(), parent.getSeqIndex(),
                    child.score(), parent.getContent(), child.rerankScore()));
        }

        // 预算截断：按管线配置的父块数量与 token 双上限，各自作用于本管线命中（入序保留）
        List<RetrievedChunk> kept = new ArrayList<>();
        Map<String, Integer> keptCountByPipeline = new LinkedHashMap<>();
        Map<String, Integer> keptTokensByPipeline = new LinkedHashMap<>();
        int truncated = 0;
        StringBuilder detail = new StringBuilder();
        for (RetrievedChunk hit : parentHits.values()) {
            IngestionPipeline pipeline = pipelinesByKey.get(hit.pipelineKey());
            int maxChunks = pipeline == null || pipeline.getParentContextMaxChunks() == null
                    ? DEFAULT_MAX_CHUNKS : pipeline.getParentContextMaxChunks();
            int maxTokens = pipeline == null || pipeline.getParentContextMaxTokens() == null
                    ? DEFAULT_MAX_TOKENS : pipeline.getParentContextMaxTokens();
            int usedCount = keptCountByPipeline.merge(hit.pipelineKey(), 0, Integer::sum);
            int usedTokens = keptTokensByPipeline.merge(hit.pipelineKey(), 0, Integer::sum);
            boolean keep = usedCount < maxChunks
                    && usedTokens + Math.max(1, tokenizer.estimateTokenCountInText(hit.text())) <= maxTokens;
            appendDetail(detail, hit, keep);
            if (keep) {
                kept.add(hit);
                keptCountByPipeline.merge(hit.pipelineKey(), 1, Integer::sum);
                keptTokensByPipeline.merge(hit.pipelineKey(),
                        Math.max(1, tokenizer.estimateTokenCountInText(hit.text())), Integer::sum);
            } else {
                truncated++;
            }
        }

        long elapsed = elapsedMillis(startedAt);
        StageStatus status = degraded > 0 ? StageStatus.DEGRADED : StageStatus.SUCCESS;
        String reason = degraded > 0 ? "PARENT_MISSING_DEGRADED_TO_CHILD" : null;
        ctx.put(ParentFetchOutcome.CONTEXT_KEY, new ParentFetchOutcome(
                kept.size(), children.size(), degraded, truncated, elapsed, status, reason, detail.toString()));
        metrics.recordParentFetch(ctx.tenantCode(), status, Duration.ofMillis(elapsed));
        log.debug("[retrieval] trace={} PARENT_FETCH children={} parents={} degraded={} truncated={} elapsed={}ms",
                ctx.traceId(), children.size(), kept.size(), degraded, truncated, elapsed);
        return new MergeResult(merged.request(), List.copyOf(kept),
                merged.searchedTables(), merged.failedTables());
    }

    /** 透传命中是否是"本应可回填"的父子管线数据（区分单级透传与异常降级）。 */
    private static boolean parentRowExpected(RetrievedChunk child, Map<String, IngestionPipeline> pipelinesByKey) {
        IngestionPipeline pipeline = pipelinesByKey.get(child.pipelineKey());
        return pipeline != null
                && com.unirag.ingestion.DocumentSplitterFactory.STRATEGY_PARENT_CHILD
                        .equalsIgnoreCase(String.valueOf(pipeline.getStrategy()).trim());
    }

    private static Long pipelineIdOf(Map<String, IngestionPipeline> pipelinesByKey, String pipelineKey) {
        IngestionPipeline pipeline = pipelinesByKey.get(pipelineKey);
        return pipeline == null ? -1L : pipeline.getId();
    }

    /** 透传命中的稳定占位键（负值避免与父块主键冲突）。 */
    private static long childSeqKey(RetrievedChunk child) {
        return ((long) Objects.hash(child.documentId(), child.pipelineKey(), child.chunkIndex())) & 0x7fffffffffffffffL;
    }

    private static void appendDetail(StringBuilder detail, RetrievedChunk hit, boolean keep) {
        if (detail.length() >= DETAIL_MAX_LENGTH) {
            return;
        }
        String entry = hit.documentId() + "/" + hit.pipelineKey() + "/" + hit.chunkIndex()
                + "=" + (keep ? "keep" : "drop") + ";";
        if (detail.length() + entry.length() <= DETAIL_MAX_LENGTH) {
            detail.append(entry);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}

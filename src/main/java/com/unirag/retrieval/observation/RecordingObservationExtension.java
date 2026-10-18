package com.unirag.retrieval.observation;

import com.unirag.retrieval.model.FailedTable;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.ObservationExtension;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageOutcome;
import com.unirag.retrieval.pipeline.StageStatus;
import com.unirag.retrieval.stage.MergeResult;
import com.unirag.retrieval.stage.PromptBundle;
import com.unirag.retrieval.stage.QueryVector;
import com.unirag.retrieval.stage.TableHits;
import com.unirag.retrieval.stage.VectorSearchResult;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把观测接入为观察型扩展（FR-012/FR-014，contracts §6）：核心阶段代码里没有一行落库逻辑，
 * 想关掉观测只需不注册本 Bean（{@code unirag.observability.enabled=false}）。
 *
 * <p>记录形态由阶段输出类型决定：查询向量化按嵌入模型每条、逐表检索按表每条（含失败表）、
 * 归并汇聚为一条并携带命中数与最高分。
 */
@Component
@ConditionalOnProperty(prefix = "unirag.observability", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RecordingObservationExtension implements ObservationExtension {

    private final ObservationRecorder recorder;

    public RecordingObservationExtension(ObservationRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void beforeStage(StageName stage, Object input, StageContext ctx) {
        // 阶段耗时由流水线在 StageOutcome 中给出，回调无需额外留痕
    }

    @Override
    public void afterStage(StageName stage, Object output, StageOutcome outcome, StageContext ctx) {
        for (StageResult result : describe(stage, output, outcome)) {
            recorder.record(ctx, result);
        }
    }

    private List<StageResult> describe(StageName stage, Object output, StageOutcome outcome) {
        if (outcome.status() == StageStatus.FAILED) {
            return List.of(entry(stage, null, outcome, null, null, null));
        }
        if (output instanceof QueryVector query) {
            return query.embeddings().isEmpty()
                    ? List.of(entry(stage, null, outcome, null, null, null))
                    : query.embeddings().keySet().stream()
                            .map(modelKey -> entry(stage, modelKey, outcome, null, null, null))
                            .toList();
        }
        if (output instanceof VectorSearchResult search) {
            return describeSearch(search, outcome);
        }
        if (output instanceof MergeResult merge) {
            return List.of(describeMerge(merge, outcome));
        }
        if (output instanceof PromptBundle bundle) {
            return List.of(entry(stage, null, outcome, null, null, bundle.citations().size()));
        }
        if (output instanceof QaAnswer answer) {
            return List.of(describeAnswer(answer, outcome));
        }
        return List.of(entry(stage, null, outcome, null, null, null));
    }

    private List<StageResult> describeSearch(VectorSearchResult search, StageOutcome outcome) {
        List<StageResult> results = new ArrayList<>();
        for (TableHits hits : search.hits()) {
            results.add(entry(StageName.VECTOR_SEARCH, hits.storeKey(), outcome,
                    hits.matches().size(), maxMatchScore(hits.matches()), null));
        }
        for (FailedTable failed : search.failedTables()) {
            results.add(entry(StageName.VECTOR_SEARCH, failed.targetKey(), StageStatus.FAILED,
                    null, null, null, null, failed.reason()));
        }
        // 无可用向量表时仍要留下本阶段记录，否则调用链在观测里断链（SC-003）
        return results.isEmpty() ? List.of(entry(StageName.VECTOR_SEARCH, null, outcome, null, null, null)) : results;
    }

    /** 部分表失败时整体仍返回，故归并记录为 DEGRADED（data-model.md §6）。 */
    private StageResult describeMerge(MergeResult merge, StageOutcome outcome) {
        StageOutcome mergeOutcome = merge.failedTables().isEmpty()
                ? outcome
                : StageOutcome.degraded(outcome.durationMs(), summarise(merge.failedTables()));
        return entry(StageName.MERGE, null, mergeOutcome,
                merge.chunks().size(), maxChunkScore(merge.chunks()), null);
    }

    /** 问答降级（检索为空）时该阶段未调用模型，按 data-model.md §6 记为 DEGRADED。 */
    private StageResult describeAnswer(QaAnswer answer, StageOutcome outcome) {
        StageOutcome answerOutcome = answer.degraded()
                ? StageOutcome.degraded(outcome.durationMs(), answer.degradeReason())
                : outcome;
        return entry(StageName.CHAT_GENERATION, null, answerOutcome,
                answer.citations().size(), null, answer.citations().size());
    }

    private static String summarise(List<FailedTable> failedTables) {
        return failedTables.stream()
                .map(failed -> failed.targetKey() + ": " + failed.reason())
                .collect(Collectors.joining("; "));
    }

    private static StageResult entry(StageName stage, String targetKey, StageOutcome outcome,
                                     Integer hitCount, Double topScore, Integer itemCount) {
        return entry(stage, targetKey, outcome.status(), outcome.durationMs(),
                hitCount, topScore, itemCount, outcome.errorMessage());
    }

    private static StageResult entry(StageName stage, String targetKey, StageStatus status, Long durationMs,
                                     Integer hitCount, Double topScore, Integer itemCount, String errorMessage) {
        return new StageResult(stage, targetKey, status, durationMs, hitCount, topScore, itemCount, errorMessage);
    }

    private static Double maxMatchScore(List<EmbeddingMatch<TextSegment>> matches) {
        return matches.stream().map(EmbeddingMatch::score).max(Double::compareTo).orElse(null);
    }

    private static Double maxChunkScore(List<RetrievedChunk> chunks) {
        return chunks.stream().map(RetrievedChunk::score).max(Double::compareTo).orElse(null);
    }
}

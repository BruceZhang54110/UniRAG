package com.unirag.eval.metric;

import com.unirag.eval.dataset.QueryCase;
import com.unirag.eval.dataset.RelevanceLabel;
import com.unirag.eval.dataset.RetrievalConfig;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.stage.VectorSearchResult;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 未命中查询的失败归因（013 research R7）。只使用可观察数据，不猜测。 */
public final class FailureAttributor {

    public static final String META_DOC_NAME = "doc_name";

    public Attribution attribute(QueryCase query,
                                 List<RetrievedChunk> production,
                                 VectorSearchResult diagnosticTables,
                                 List<RetrievedChunk> diagnosticMerged,
                                 List<ChunkView> allChunks,
                                 RetrievalConfig config) {
        if (!query.answerable() || production.stream().anyMatch(chunk -> matches(chunk, query))) {
            return Attribution.none();
        }
        if (query.relevant().isEmpty()) {
            return new Attribution(FailureCategory.LABEL_INVALID,
                    List.of("可回答查询没有相关性标注: " + query.id()));
        }

        for (RelevanceLabel label : query.relevant()) {
            Attribution attribution = attributeLabel(label, diagnosticTables, diagnosticMerged,
                    allChunks, config);
            if (attribution.isFailure()) {
                return attribution;
            }
        }
        return new Attribution(FailureCategory.LABEL_INVALID,
                List.of("所有标注均能命中生产结果，但匹配逻辑未识别: " + query.id()));
    }

    public Attribution attributeUnanswerable(QueryCase query, List<RetrievedChunk> production) {
        if (query.answerable() || production.isEmpty()) {
            return Attribution.none();
        }
        return new Attribution(FailureCategory.UNANSWERABLE_FALSE_POSITIVE,
                List.of("不可回答查询返回了 " + production.size() + " 个片段"));
    }

    private Attribution attributeLabel(RelevanceLabel label,
                                       VectorSearchResult diagnosticTables,
                                       List<RetrievedChunk> diagnosticMerged,
                                       List<ChunkView> allChunks,
                                       RetrievalConfig config) {
        List<String> evidence = new ArrayList<>();
        String labelText = label.doc() + " / \"" + preview(label.span()) + "\"";

        boolean existsInChunks = allChunks.stream()
                .anyMatch(chunk -> chunk.doc().equals(label.doc())
                        && NormalizedText.contains(chunk.text(), label.span()));
        if (!existsInChunks) {
            evidence.add("答案片段未被任何切片覆盖: " + labelText);
            return new Attribution(FailureCategory.CHUNKING_GAP, evidence);
        }
        evidence.add("片段已存在于 document_chunk: " + labelText);

        boolean inTables = diagnosticTables.hits().stream()
                .flatMap(hits -> hits.matches().stream())
                .anyMatch(match -> tableMatch(match, label));
        if (!inTables) {
            evidence.add("逐表诊断（minScore=0, topK="
                    + diagnosticTables.query().request().topK() + "）未召回该片段");
            return new Attribution(FailureCategory.NOT_RECALLED, evidence);
        }
        evidence.add("逐表诊断已召回该片段");

        Optional<RankedChunk> merged = diagnosticMerged.stream()
                .filter(chunk -> chunk.docName() != null && chunk.docName().equals(label.doc())
                        && NormalizedText.contains(chunk.text(), label.span()))
                .findFirst()
                .map(chunk -> new RankedChunk(diagnosticMerged.indexOf(chunk) + 1, chunk));
        if (merged.isEmpty()) {
            evidence.add("逐表命中存在，但归并后的 top-"
                    + diagnosticTables.query().request().topK() + " 中丢失");
            return new Attribution(FailureCategory.MERGE_OR_TOPK_DROPPED, evidence);
        }

        RankedChunk candidate = merged.get();
        evidence.add("诊断遍归并排名=" + candidate.rank() + ", score=" + format(candidate.chunk().score())
                + ", minScore=" + config.minScore() + ", topK=" + config.topK());

        if (candidate.chunk().score() < config.minScore()) {
            evidence.add("分数低于生产阈值，被 minScore 过滤");
            return new Attribution(FailureCategory.THRESHOLD_FILTERED, evidence);
        }
        if (config.rerank()) {
            evidence.add("重排启用，候选在重排后未保留");
            return new Attribution(FailureCategory.RERANK_DROPPED, evidence);
        }
        if (candidate.rank() > config.topK()) {
            evidence.add("排名高于生产 topK，被截断");
            return new Attribution(FailureCategory.BELOW_TOPK, evidence);
        }
        evidence.add("候选满足阈值与 topK，但生产结果缺失；请检查归并/过滤逻辑");
        return new Attribution(FailureCategory.LABEL_INVALID, evidence);
    }

    private static boolean matches(RetrievedChunk chunk, QueryCase query) {
        return query.relevant().stream().anyMatch(label ->
                chunk.docName() != null && chunk.docName().equals(label.doc())
                        && NormalizedText.contains(chunk.text(), label.span()));
    }

    private static boolean tableMatch(EmbeddingMatch<TextSegment> match, RelevanceLabel label) {
        Metadata metadata = match.embedded().metadata();
        String doc = metadata.getString(META_DOC_NAME);
        return doc != null && doc.equals(label.doc())
                && NormalizedText.contains(match.embedded().text(), label.span());
    }

    private static String format(double value) {
        return String.format("%.4f", value);
    }

    private static String preview(String value) {
        String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 60) + "…";
    }

    public record ChunkView(String doc, String text) {
    }

    private record RankedChunk(int rank, RetrievedChunk chunk) {
    }
}

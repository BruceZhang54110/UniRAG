package com.unirag.ingestion;

import com.unirag.infrastructure.persistence.entity.Document;
import com.unirag.infrastructure.persistence.entity.DocumentChunk;
import com.unirag.infrastructure.persistence.entity.IngestionPipeline;
import com.unirag.infrastructure.persistence.entity.IngestionRun;
import com.unirag.infrastructure.persistence.service.DocumentChunkService;
import com.unirag.infrastructure.persistence.service.IngestionRunService;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 单管线执行器（research.md R1/R6）：切片 → 向量化 → 写租户向量表 → 回链落库 → 记执行记录。
 *
 * <p>替换语义"先清后写"：先删本 (document, pipeline) 旧 chunk 行并按旧 embedding_id 删旧向量，
 * 再写入新片段。中途失败 best-effort removeAll 本 run 已写向量，记 FAILED run（原因截断 500 字符、
 * 不含任何密钥/配置原文，FR-010）；管线之间无共享事务，故障互不影响（FR-004）。
 */
@Slf4j
@Service
public class PipelineExecutor {

    static final String META_DOC_NAME = "doc_name";
    static final String META_DOCUMENT_ID = "document_id";
    static final String META_PIPELINE_KEY = "pipeline_key";
    static final String META_CHUNK_INDEX = "chunk_index";
    static final String META_DOCUMENT_VERSION_ID = "document_version_id";

    static final int MAX_ERROR_LENGTH = 500;

    private final TenantResourceRouter router;
    private final DocumentChunkService chunkService;
    private final IngestionRunService runService;
    private final IngestionTokenizer tokenizer;

    public PipelineExecutor(TenantResourceRouter router,
                            DocumentChunkService chunkService,
                            IngestionRunService runService,
                            IngestionTokenizer tokenizer) {
        this.router = router;
        this.chunkService = chunkService;
        this.runService = runService;
        this.tokenizer = tokenizer;
    }

    /**
     * 对单条管线执行完整摄取流程；任何失败都收敛为 FAILED 的 {@link RunOutcome}，不向调用方抛出。
     *
     * @param tenantCode 租户码（资源解析用）
     * @param document   已落库文档（id 供 metadata 回链）
     * @param pipeline   管线配置
     * @param content    文档内容
     */
    public RunOutcome execute(String tenantCode, Document document, IngestionPipeline pipeline, String content) {
        Date startedAt = new Date();
        EmbeddingStore<TextSegment> store = null;
        List<String> writtenIds = List.of();
        try {
            IngestionRun previousRun = findPreviousRun(document, pipeline);
            if (previousRun != null && RunStatus.SUCCESS.name().equals(previousRun.getStatus())) {
                return RunOutcome.success(pipeline, previousRun.getChunkCount());
            }
            if (previousRun != null) {
                runService.removeById(previousRun.getId());
            }

            EmbeddingModel model = router.get(tenantCode, ResourceType.EMBEDDING_MODEL,
                    pipeline.getEmbeddingModelKey(), EmbeddingModel.class);
            store = router.get(tenantCode, ResourceType.EMBEDDING_STORE,
                    pipeline.getEmbeddingStoreKey(), EmbeddingStore.class);

            clearOldChunks(document.getId(), document.getIngestionVersionId(), pipeline.getId(), store);

            List<TextSegment> segments = split(pipeline, document, content);
            Response<List<Embedding>> response = model.embedAll(segments);
            writtenIds = store.generateIds(segments.size());
            store.addAll(writtenIds, response.content(), segments);

            List<DocumentChunk> chunks = toChunks(document, pipeline, segments, writtenIds);
            if (document.getIngestionVersionId() != null) {
                chunks.forEach(chunk -> chunk.setDocumentVersionId(document.getIngestionVersionId()));
            }
            chunkService.saveBatch(chunks);

            saveRun(document, pipeline, RunStatus.SUCCESS, chunks.size(), null, startedAt);
            log.info("Pipeline ingestion succeeded: tenant={}, doc={}, pipeline={}, chunks={}",
                    tenantCode, document.getDocName(), pipeline.getPipelineKey(), chunks.size());
            return RunOutcome.success(pipeline, chunks.size());
        } catch (Exception e) {
            bestEffortRemove(store, writtenIds);
            String reason = truncate(rootCauseMessage(e));
            log.error("Pipeline ingestion failed: tenant={}, doc={}, pipeline={}, reason={}",
                    tenantCode, document.getDocName(), pipeline.getPipelineKey(), reason);
            if (findPreviousRun(document, pipeline) == null) {
                saveRun(document, pipeline, RunStatus.FAILED, 0, reason, startedAt);
            }
            return RunOutcome.failed(pipeline, reason);
        }
    }

    private IngestionRun findPreviousRun(Document document, IngestionPipeline pipeline) {
        if (document.getIngestionJobId() == null && document.getIngestionVersionId() == null) {
            return null;
        }
        var query = runService.lambdaQuery().eq(IngestionRun::getDocumentId, document.getId())
                .eq(IngestionRun::getPipelineId, pipeline.getId());
        if (document.getIngestionJobId() != null) {
            query.eq(IngestionRun::getJobId, document.getIngestionJobId());
        } else if (document.getIngestionVersionId() != null) {
            query.eq(IngestionRun::getDocumentVersionId, document.getIngestionVersionId());
        }
        return query.last("LIMIT 1").one();
    }

    /** 先清：删旧 chunk 行 + 按旧 embedding_id 删旧向量（research.md R6）。 */
    private void clearOldChunks(Long documentId, Long versionId, Long pipelineId, EmbeddingStore<TextSegment> store) {
        List<DocumentChunk> oldChunks = versionId == null
                ? chunkService.findByDocumentAndPipeline(documentId, pipelineId)
                : chunkService.lambdaQuery().eq(DocumentChunk::getDocumentId, documentId)
                .eq(DocumentChunk::getDocumentVersionId, versionId)
                .eq(DocumentChunk::getPipelineId, pipelineId).list();
        List<String> oldEmbeddingIds = oldChunks.stream()
                .map(DocumentChunk::getEmbeddingId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (!oldEmbeddingIds.isEmpty()) {
            store.removeAll(oldEmbeddingIds);
        }
        if (versionId == null) {
            chunkService.deleteByDocumentAndPipeline(documentId, pipelineId);
        } else {
            chunkService.lambdaUpdate().eq(DocumentChunk::getDocumentId, documentId)
                    .eq(DocumentChunk::getDocumentVersionId, versionId)
                    .eq(DocumentChunk::getPipelineId, pipelineId).remove();
        }
    }

    /** 切片并附加契约四键 metadata（contracts §3）。 */
    private List<TextSegment> split(IngestionPipeline pipeline, Document document, String content) {
        DocumentSplitter splitter = DocumentSplitterFactory.create(
                pipeline.getStrategy(), pipeline.getMaxSegmentTokens(), pipeline.getOverlapTokens(), tokenizer);
        List<TextSegment> raw = splitter.split(dev.langchain4j.data.document.Document.from(content));
        List<TextSegment> segments = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            // 新建 metadata（勿 copy 切片器产物：langchain4j 会自带 "index" 键），保证恰好契约四键（contracts §3）
            Metadata metadata = new Metadata();
            metadata.put(META_DOC_NAME, document.getDocName());
            metadata.put(META_DOCUMENT_ID, document.getId());
            metadata.put(META_PIPELINE_KEY, pipeline.getPipelineKey());
            metadata.put(META_CHUNK_INDEX, i);
            if (document.getIngestionVersionId() != null) {
                metadata.put(META_DOCUMENT_VERSION_ID, document.getIngestionVersionId());
            }
            segments.add(TextSegment.from(raw.get(i).text(), metadata));
        }
        return segments;
    }

    private List<DocumentChunk> toChunks(Document document, IngestionPipeline pipeline,
                                         List<TextSegment> segments, List<String> embeddingIds) {
        Date now = new Date();
        List<DocumentChunk> chunks = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(document.getId());
            chunk.setDocumentVersionId(document.getIngestionVersionId());
            chunk.setPipelineId(pipeline.getId());
            chunk.setSeqIndex(i);
            chunk.setContent(segments.get(i).text());
            chunk.setTokenCount(tokenizer.estimateTokens(segments.get(i).text()));
            chunk.setEmbeddingId(embeddingIds.get(i));
            chunk.setCreatedAt(now);
            chunks.add(chunk);
        }
        return chunks;
    }

    private void saveRun(Document document, IngestionPipeline pipeline, RunStatus status,
                         int chunkCount, String errorMessage, Date startedAt) {
        IngestionRun run = new IngestionRun();
        run.setDocumentId(document.getId());
        run.setDocumentVersionId(document.getIngestionVersionId());
        run.setJobId(document.getIngestionJobId());
        run.setPipelineId(pipeline.getId());
        run.setStatus(status.name());
        run.setChunkCount(chunkCount);
        run.setErrorMessage(errorMessage);
        run.setStartedAt(startedAt);
        run.setFinishedAt(new Date());
        runService.save(run);
    }

    /** best-effort 清理本 run 已写向量：清理失败仅告警，不掩盖原始失败原因（research.md R6）。 */
    private void bestEffortRemove(EmbeddingStore<TextSegment> store, List<String> embeddingIds) {
        if (store == null || embeddingIds.isEmpty()) {
            return;
        }
        try {
            store.removeAll(embeddingIds);
        } catch (Exception cleanup) {
            log.warn("Best-effort cleanup of written embeddings failed, reason={}", rootCauseMessage(cleanup));
        }
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause.getClass().getSimpleName();
        }
        return message;
    }

    private static String truncate(String message) {
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}

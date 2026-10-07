package com.unirag.ingestion;

import com.unirag.infrastructure.mp.entity.Document;
import com.unirag.infrastructure.mp.entity.IngestionPipeline;
import com.unirag.infrastructure.mp.entity.IngestionRun;
import com.unirag.infrastructure.mp.entity.Tenant;
import com.unirag.infrastructure.mp.service.DocumentService;
import com.unirag.infrastructure.mp.service.IngestionPipelineService;
import com.unirag.infrastructure.mp.service.IngestionRunService;
import com.unirag.infrastructure.mp.service.TenantService;
import com.unirag.ingestion.support.DocumentFingerprint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 摄取入口（contracts §1）：按租户全部启用管线执行"切片→向量化→写向量表→元数据回链"。
 *
 * <p>语义保证：
 * <ul>
 *   <li>仅入参非法（缺 tenant/空内容）抛 {@link IllegalArgumentException}；单管线失败收敛在
 *       {@link RunOutcome}，不抛出（FR-004）；</li>
 *   <li>同 (tenant, docName) JVM 内串行化（分段锁，research.md R5），并发摄取确定性收尾（FR-007）；</li>
 *   <li>指纹相同 → NOOP（数据不动、version 不变、run 记 SUCCESS/0 片段便于观测，contracts §5）；
 *       指纹不同 → 替换（管线内先清后写，version+1）；</li>
 *   <li>任何 outcome/run 字段不含密钥（FR-010）。</li>
 * </ul>
 */
@Slf4j
@Service
public class IngestionService {

    /** 并发分段锁：同 (tenant, docName) 串行，不同文档并行（FR-007，research.md R5）。 */
    private static final int LOCK_STRIPES = 64;

    private final TenantService tenantService;
    private final IngestionPipelineService pipelineService;
    private final DocumentService documentService;
    private final IngestionRunService runService;
    private final PipelineExecutor pipelineExecutor;
    private final Lock[] docLocks = new Lock[LOCK_STRIPES];

    public IngestionService(TenantService tenantService,
                            IngestionPipelineService pipelineService,
                            DocumentService documentService,
                            IngestionRunService runService,
                            PipelineExecutor pipelineExecutor) {
        this.tenantService = tenantService;
        this.pipelineService = pipelineService;
        this.documentService = documentService;
        this.runService = runService;
        this.pipelineExecutor = pipelineExecutor;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            docLocks[i] = new ReentrantLock();
        }
    }

    /**
     * 摄取一份文档：同步执行该租户全部启用管线后才返回。
     */
    public IngestOutcome ingest(IngestRequest request) {
        validate(request);
        Lock lock = docLocks[Math.floorMod((request.tenantCode() + "|" + request.docName()).hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            return doIngest(request);
        } finally {
            lock.unlock();
        }
    }

    private IngestOutcome doIngest(IngestRequest request) {
        Tenant tenant = tenantService.findByCode(request.tenantCode());
        if (tenant == null) {
            throw new IllegalArgumentException("Unknown tenant: " + request.tenantCode());
        }
        List<IngestionPipeline> pipelines = pipelineService.findEnabledByTenant(tenant.getId());
        String fingerprint = DocumentFingerprint.sha256(request.content());
        Document document = documentService.findByTenantAndName(tenant.getId(), request.docName());

        if (request.versionId() != null) {
            return doVersionedIngest(request, tenant, pipelines, document, fingerprint);
        }

        if (document != null && fingerprint.equals(document.getContentFingerprint())) {
            // 同内容重复摄取：no-op，数据不动（contracts §5 / research.md R4）
            List<RunOutcome> noopRuns = recordNoopRuns(document, pipelines);
            log.info("Ingestion no-op (same content): tenant={}, doc={}, version={}",
                    request.tenantCode(), request.docName(), document.getVersion());
            return new IngestOutcome(document.getId(), request.docName(),
                    IngestOutcome.Status.NOOP, document.getVersion(), noopRuns, null);
        }

        Document saved = upsertDocument(request, tenant.getId(), fingerprint, document);
        if (pipelines.isEmpty()) {
            String reason = "No enabled ingestion pipeline for tenant: " + request.tenantCode();
            markFailed(saved, reason);
            log.error("Ingestion failed: tenant={}, doc={}, reason={}", request.tenantCode(), request.docName(), reason);
            return new IngestOutcome(saved.getId(), request.docName(),
                    IngestOutcome.Status.FAILED, saved.getVersion(), List.of(), reason);
        }

        List<RunOutcome> runs = new ArrayList<>(pipelines.size());
        for (IngestionPipeline pipeline : pipelines) {
            runs.add(pipelineExecutor.execute(request.tenantCode(), saved, pipeline, request.content()));
        }

        DocumentStatus finalStatus = summarize(runs);
        int totalChunks = runs.stream().mapToInt(RunOutcome::chunkCount).sum();
        saved.setStatus(finalStatus.name());
        saved.setChunkCount(totalChunks);
        saved.setUpdatedAt(new Date());
        documentService.updateById(saved);

        log.info("Ingestion finished: tenant={}, doc={}, status={}, version={}, chunks={}",
                request.tenantCode(), request.docName(), finalStatus, saved.getVersion(), totalChunks);
        return new IngestOutcome(saved.getId(), request.docName(),
                IngestOutcome.Status.valueOf(finalStatus.name()), saved.getVersion(), runs, null);
    }

    private IngestOutcome doVersionedIngest(IngestRequest request, Tenant tenant,
                                            List<IngestionPipeline> pipelines, Document document,
                                            String fingerprint) {
        if (document == null || !fingerprint.equals(request.expectedFingerprint())) {
            throw new IllegalStateException("INGESTION_VERSION_CONTENT_MISMATCH");
        }
        document.setIngestionVersionId(request.versionId());
        document.setIngestionJobId(request.jobId());
        if (pipelines.isEmpty()) {
            return new IngestOutcome(document.getId(), request.docName(), IngestOutcome.Status.FAILED,
                    document.getVersion(), List.of(), "NO_ENABLED_PIPELINE");
        }
        List<RunOutcome> runs = pipelines.stream()
                .map(pipeline -> pipelineExecutor.execute(request.tenantCode(), document, pipeline, request.content()))
                .toList();
        DocumentStatus status = summarize(runs);
        return new IngestOutcome(document.getId(), request.docName(),
                IngestOutcome.Status.valueOf(status.name()), request.versionId().intValue(), runs,
                status == DocumentStatus.INGESTED ? null : "PIPELINE_FAILED");
    }

    /** 新文档插入 INGESTING 行（先拿 id 供向量 metadata 回链）；既有文档替换：version+1、状态归位 INGESTING。 */
    private Document upsertDocument(IngestRequest request, Long tenantId, String fingerprint, Document existing) {
        Date now = new Date();
        if (existing == null) {
            Document document = new Document();
            document.setTenantId(tenantId);
            document.setDocName(request.docName());
            document.setTitle(request.title());
            document.setContentFingerprint(fingerprint);
            document.setStatus(DocumentStatus.INGESTING.name());
            document.setChunkCount(0);
            document.setVersion(1);
            document.setCreatedAt(now);
            document.setUpdatedAt(now);
            document.setDeleted(false);
            documentService.save(document);
            return document;
        }
        existing.setTitle(request.title() != null ? request.title() : existing.getTitle());
        existing.setContentFingerprint(fingerprint);
        existing.setStatus(DocumentStatus.INGESTING.name());
        existing.setChunkCount(0);
        existing.setVersion(existing.getVersion() + 1);
        existing.setUpdatedAt(now);
        documentService.updateById(existing);
        return existing;
    }

    /** NOOP 观察性记录：每启用管线一条 SUCCESS/0 片段 run（data-model.md 校验规则）。 */
    private List<RunOutcome> recordNoopRuns(Document document, List<IngestionPipeline> pipelines) {
        Date now = new Date();
        List<RunOutcome> runs = new ArrayList<>(pipelines.size());
        for (IngestionPipeline pipeline : pipelines) {
            IngestionRun run = new IngestionRun();
            run.setDocumentId(document.getId());
            run.setPipelineId(pipeline.getId());
            run.setStatus(RunStatus.SUCCESS.name());
            run.setChunkCount(0);
            run.setStartedAt(now);
            run.setFinishedAt(now);
            runService.save(run);
            runs.add(RunOutcome.noop(pipeline));
        }
        return runs;
    }

    private void markFailed(Document document, String reason) {
        document.setStatus(DocumentStatus.FAILED.name());
        document.setUpdatedAt(new Date());
        documentService.updateById(document);
    }

    private DocumentStatus summarize(List<RunOutcome> runs) {
        long successCount = runs.stream().filter(r -> r.status() == RunStatus.SUCCESS).count();
        if (successCount == runs.size()) {
            return DocumentStatus.INGESTED;
        }
        return successCount > 0 ? DocumentStatus.PARTIAL : DocumentStatus.FAILED;
    }

    private void validate(IngestRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("IngestRequest must not be null");
        }
        if (request.tenantCode() == null || request.tenantCode().isBlank()) {
            throw new IllegalArgumentException("tenantCode must not be blank");
        }
        if (request.docName() == null || request.docName().isBlank()) {
            throw new IllegalArgumentException("docName must not be blank");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}

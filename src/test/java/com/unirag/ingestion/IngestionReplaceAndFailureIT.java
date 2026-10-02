package com.unirag.ingestion;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 验收（spec.md FR-004/006/007，contracts §5，SC-004）：
 * 同内容二次摄取 NOOP（行数不翻倍、version 不变）；改内容重摄取 version+1、旧 chunk 行与旧向量已删；
 * 两线程并发摄取同 (tenant, docName) 确定性收尾（不翻倍、仅一个 version 增量）；
 * 失败管线 run 记录可查（原因 ≤500 字符且无密钥串，FR-010）。
 * 租户码全局唯一：{@code repUS3} / {@code repFailUS3}。
 */
class IngestionReplaceAndFailureIT extends AbstractIngestionIT {

    private static final String TENANT = "repUS3";
    private static final String FAIL_TENANT = "repFailUS3";
    private static final String TABLE = "embeddings_repus3";
    private static final String FAIL_TABLE = "embeddings_repfailus3";
    private static final String DOC = "replace-doc";
    private static final String CONCURRENT_DOC = "concurrent-doc";

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "default", TABLE, 1024);
        insertPipeline(tenantId, "main", 500, 50, "default", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("default", storeBean(TENANT, "default")));
    }

    @Test
    void scenario1_repeatIngestSameContentIsNoop() {
        IngestOutcome first = ingestionService.ingest(
                new IngestRequest(TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));
        assertThat(first.status()).isEqualTo(IngestOutcome.Status.INGESTED);
        long chunksAfterFirst = chunkRows(first.documentId());
        long vectorsAfterFirst = vectorRows(TABLE);

        // 同内容二次摄取（FR-006/SC-004）：NOOP，数据不动，version 不变，行数不翻倍
        IngestOutcome second = ingestionService.ingest(
                new IngestRequest(TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));
        assertThat(second.status()).isEqualTo(IngestOutcome.Status.NOOP);
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(second.version()).isEqualTo(first.version());
        assertThat(chunkRows(second.documentId())).isEqualTo(chunksAfterFirst);
        assertThat(vectorRows(TABLE)).isEqualTo(vectorsAfterFirst);
        // NOOP 观察性记录：每启用管线一条 SUCCESS/0 片段 run（contracts §5，便于观测）
        assertThat(second.runs()).hasSize(1);
        assertThat(second.runs().get(0).status()).isEqualTo(RunStatus.SUCCESS);
        assertThat(second.runs().get(0).chunkCount()).isZero();
        // 文档状态保持 INGESTED（NOOP 不改变生命周期状态）
        assertThat(documentStatus(second.documentId())).isEqualTo("INGESTED");
    }

    @Test
    void scenario2_revisedContentReplacesOldData() {
        IngestOutcome first = ingestionService.ingest(
                new IngestRequest(TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));
        List<String> oldEmbeddingIds = jdbcTemplate.queryForList(
                "SELECT embedding_id FROM document_chunk WHERE document_id = ?", String.class, first.documentId());
        assertThat(oldEmbeddingIds).isNotEmpty();

        // 内容变更重摄取：替换语义——旧片段与旧向量全删，写入新片段，version+1（contracts §5）
        IngestOutcome second = ingestionService.ingest(new IngestRequest(
                TENANT, DOC, null, IngestionTestDocuments.longChineseDocumentRevised()));
        assertThat(second.status()).isEqualTo(IngestOutcome.Status.INGESTED);
        assertThat(second.version()).isEqualTo(first.version() + 1);

        // 旧 chunk 行已删
        Long oldChunksLeft = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND embedding_id = ANY (?)",
                Long.class, second.documentId(), oldEmbeddingIds.toArray(new String[0]));
        assertThat(oldChunksLeft).isZero();
        // 旧向量已删（向量表旧 embedding_id 不存在）
        for (String oldId : oldEmbeddingIds) {
            assertThat(vectorRowById(TABLE, oldId))
                    .as("旧向量 embedding_id=%s 已被清理", oldId)
                    .isZero();
        }
        // 新片段生效：行数与新内容一致且回链完好（行数未因替换而累积）
        int newChunks = second.runs().get(0).chunkCount();
        assertThat(newChunks).isPositive();
        assertThat(chunkRows(second.documentId())).isEqualTo(newChunks);
        assertThat(vectorRows(TABLE)).isEqualTo(newChunks);
        Long revisedMarker = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + TABLE + " WHERE text LIKE '%修订版%'", Long.class);
        assertThat(revisedMarker).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void scenario3_concurrentIngestSameDocumentIsDeterministic() throws Exception {
        // 预备：文档已处于 v1（INGESTED）
        IngestOutcome base = ingestionService.ingest(
                new IngestRequest(TENANT, CONCURRENT_DOC, null, IngestionTestDocuments.longChineseDocument()));
        assertThat(base.version()).isEqualTo(1);

        // 两线程并发摄取同 (tenant, docName) 同内容（新内容）：串行化后确定性收尾（FR-007）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        Callable<IngestOutcome> task = () -> {
            startGate.await();
            return ingestionService.ingest(new IngestRequest(
                    TENANT, CONCURRENT_DOC, null, IngestionTestDocuments.longChineseDocumentRevised()));
        };
        Future<IngestOutcome> future1 = pool.submit(task);
        Future<IngestOutcome> future2 = pool.submit(task);
        startGate.countDown();
        IngestOutcome outcome1 = future1.get(60, TimeUnit.SECONDS);
        IngestOutcome outcome2 = future2.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 一个 INGESTED（+1 版）一个 NOOP，仅一个 version 增量，行数确定不翻倍
        assertThat(List.of(outcome1.status(), outcome2.status()))
                .containsExactlyInAnyOrder(IngestOutcome.Status.INGESTED, IngestOutcome.Status.NOOP);
        assertThat(outcome1.version()).isEqualTo(2);
        assertThat(outcome2.version()).isEqualTo(2);
        int ingestedChunks = outcome1.status() == IngestOutcome.Status.INGESTED
                ? outcome1.runs().get(0).chunkCount()
                : outcome2.runs().get(0).chunkCount();
        assertThat(chunkRows(base.documentId())).isEqualTo(ingestedChunks);
        assertThat(vectorRows(TABLE)).isEqualTo(ingestedChunks);
        assertThat(documentVersion(base.documentId())).isEqualTo(2);
    }

    @Test
    void scenario4_failedRunIsObservableAndSecretFree() {
        cleanupTenant(FAIL_TENANT, FAIL_TABLE);
        long failTenantId = insertTenant(FAIL_TENANT);
        insertStoreConfig(failTenantId, "good", FAIL_TABLE, 1024);
        insertPipeline(failTenantId, "goodPipe", 500, 50, "good", "default");
        insertPipeline(failTenantId, "brokenPipe", 500, 50, "ghostStore", "default");
        registrar.loadAndRegister();
        stubRouter(FAIL_TENANT,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("good", storeBean(FAIL_TENANT, "good")));
        stubMissingStore(FAIL_TENANT, "ghostStore");

        IngestOutcome outcome = ingestionService.ingest(
                new IngestRequest(FAIL_TENANT, "fail-doc", null, IngestionTestDocuments.longChineseDocument()));
        // 成功管线数据保留，失败管线有明确原因记录，整体 PARTIAL（FR-004）
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.PARTIAL);
        assertThat(documentStatus(outcome.documentId())).isEqualTo("PARTIAL");

        Map<String, Object> failedRun = jdbcTemplate.queryForMap(
                "SELECT error_message, chunk_count FROM ingestion_run "
                        + "WHERE document_id = ? AND status = 'FAILED'", outcome.documentId());
        String reason = (String) failedRun.get("error_message");
        assertThat(reason).contains("ghostStore");
        assertThat(((Number) failedRun.get("chunk_count")).intValue()).isZero();
        // 原因 ≤500 字符（data-model.md）且无密钥串（FR-010）
        assertThat(reason.length()).isLessThanOrEqualTo(500);
        String password = environment.getProperty("spring.datasource.password");
        assertThat(reason).doesNotContain(password);
        assertThat(reason).doesNotContainIgnoringCase("api-key").doesNotContainIgnoringCase("apikey");
        // 成功管线向量保留且行数一致
        long goodPipeId = jdbcTemplate.queryForObject(
                "SELECT id FROM ingestion_pipeline WHERE tenant_id = ? AND pipeline_key = 'goodPipe'",
                Long.class, failTenantId);
        Long goodChunks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND pipeline_id = ?",
                Long.class, outcome.documentId(), goodPipeId);
        assertThat(vectorRows(FAIL_TABLE)).isEqualTo(goodChunks);
    }

    private long chunkRows(long documentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ?", Long.class, documentId);
    }

    private long vectorRows(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long vectorRowById(String table, String embeddingId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE embedding_id = ?::uuid", Long.class, embeddingId);
    }

    private String documentStatus(long documentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM document WHERE id = ?", String.class, documentId);
    }

    private int documentVersion(long documentId) {
        return jdbcTemplate.queryForObject("SELECT version FROM document WHERE id = ?", Integer.class, documentId);
    }
}

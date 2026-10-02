package com.unirag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 验收（spec.md FR-001/003/005，contracts §1/§3/§4，SC-002）：
 * 单管线摄取完整闭环——切片行数 = 向量表行数、逐片 embedding_id 回链且 metadata 恰含四键、
 * 片段 token ≤ 上限、双租户隔离、短文档恰好一片、文档状态/片段数/执行记录可查。
 * 嵌入为本地确定性替身（FR-009）。租户码全局唯一：{@code ingUS1} / {@code ingPeerUS1}。
 */
class DocumentIngestionIT extends AbstractIngestionIT {

    private static final String TENANT = "ingUS1";
    private static final String PEER = "ingPeerUS1";
    private static final String OWN_TABLE = "embeddings_ingus1";
    private static final String PEER_TABLE = "embeddings_ingpeerus1";
    private static final String DOC = "kb-ingest-guide";
    private static final String PEER_DOC = "peer-only-guide";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, OWN_TABLE);
        cleanupTenant(PEER, PEER_TABLE);
        long own = insertTenant(TENANT);
        insertStoreConfig(own, "default", OWN_TABLE, 1024);
        insertPipeline(own, "coarse", 500, 50, "default", "default");
        long peer = insertTenant(PEER);
        insertStoreConfig(peer, "default", PEER_TABLE, 1024);
        insertPipeline(peer, "coarse", 500, 50, "default", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("default", storeBean(TENANT, "default")));
        stubRouter(PEER,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("default", storeBean(PEER, "default")));
    }

    @Test
    void scenario1_singlePipelineIngestionAndBacklink() throws Exception {
        IngestOutcome outcome = ingestionService.ingest(
                new IngestRequest(TENANT, DOC, "知识库指南", IngestionTestDocuments.longChineseDocument()));

        // 摄取闭环：一次调用即就绪（FR-001）
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.INGESTED);
        assertThat(outcome.version()).isEqualTo(1);
        assertThat(outcome.runs()).hasSize(1);
        RunOutcome run = outcome.runs().get(0);
        assertThat(run.status()).isEqualTo(RunStatus.SUCCESS);
        assertThat(run.pipelineKey()).isEqualTo("coarse");
        assertThat(run.chunkCount()).isGreaterThan(1);

        long documentId = outcome.documentId();

        // 场景 4（验收）：文档状态 INGESTED、chunk_count 与实际一致、ingestion_run SUCCESS 一条
        Map<String, Object> documentRow = jdbcTemplate.queryForMap(
                "SELECT status, chunk_count, version FROM document WHERE id = ?", documentId);
        assertThat(documentRow.get("status")).isEqualTo("INGESTED");
        assertThat(((Number) documentRow.get("chunk_count")).intValue()).isEqualTo(run.chunkCount());
        assertThat(((Number) documentRow.get("version")).intValue()).isEqualTo(1);
        List<Map<String, Object>> runRows = jdbcTemplate.queryForList(
                "SELECT status, chunk_count FROM ingestion_run WHERE document_id = ?", documentId);
        assertThat(runRows).hasSize(1);
        assertThat(runRows.get(0).get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) runRows.get(0).get("chunk_count")).intValue()).isEqualTo(run.chunkCount());

        // 场景 1（验收）：chunk 行数 = 向量表行数
        Long chunkRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ?", Long.class, documentId);
        Long vectorRows = jdbcTemplate.queryForObject("SELECT count(*) FROM " + OWN_TABLE, Long.class);
        assertThat(chunkRows).isEqualTo((long) run.chunkCount());
        assertThat(vectorRows).isEqualTo(chunkRows);

        // 逐片回链（contracts §4 双向回链）：embedding_id 非空、向量记录存在、metadata 恰含四键且值与本行一致
        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(
                "SELECT seq_index, embedding_id, token_count FROM document_chunk WHERE document_id = ? ORDER BY seq_index",
                documentId);
        assertThat(chunks).hasSize(run.chunkCount());
        for (Map<String, Object> chunk : chunks) {
            int seqIndex = ((Number) chunk.get("seq_index")).intValue();
            String embeddingId = (String) chunk.get("embedding_id");
            int tokenCount = ((Number) chunk.get("token_count")).intValue();
            // 片段 token 数 ≤ 上限（FR-008）
            assertThat(embeddingId).isNotBlank();
            assertThat(tokenCount).isPositive().isLessThanOrEqualTo(500);

            Long vectorExists = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + OWN_TABLE + " WHERE embedding_id = ?::uuid", Long.class, embeddingId);
            assertThat(vectorExists).as("向量表存在 embedding_id=%s 的记录", embeddingId).isEqualTo(1L);

            String metadataJson = jdbcTemplate.queryForObject(
                    "SELECT metadata FROM " + OWN_TABLE + " WHERE embedding_id = ?::uuid", String.class, embeddingId);
            JsonNode metadata = OBJECT_MAPPER.readTree(metadataJson);
            assertThat(metadataKeys(metadata))
                    .as("metadata 恰含契约四键（contracts §3）")
                    .containsExactlyInAnyOrder("doc_name", "document_id", "pipeline_key", "chunk_index");
            assertThat(metadata.get("doc_name").asText()).isEqualTo(DOC);
            assertThat(metadata.get("document_id").asLong()).isEqualTo(documentId);
            assertThat(metadata.get("pipeline_key").asText()).isEqualTo("coarse");
            assertThat(metadata.get("chunk_index").asInt()).isEqualTo(seqIndex);
        }
    }

    @Test
    void scenario2_crossTenantSearchIsolated() {
        ingestionService.ingest(new IngestRequest(TENANT, DOC, null, IngestionTestDocuments.longChineseDocument()));
        String peerContent = "佩尔租户专有内容：火星种植基地的土壤改良方案与轨道运输排期。";
        ingestionService.ingest(new IngestRequest(PEER, PEER_DOC, null, peerContent));

        EmbeddingModel model = new DeterministicEmbeddingModel(1024);
        EmbeddingStore<TextSegment> ownStore = storeBean(TENANT, "default");
        EmbeddingStore<TextSegment> peerStore = storeBean(PEER, "default");

        // 本方检索：以本方片段原文查询，top1 精确命中（回链一致）
        String ownChunkText = jdbcTemplate.queryForObject(
                "SELECT content FROM document_chunk c JOIN document d ON d.id = c.document_id "
                        + "WHERE d.tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) ORDER BY c.seq_index LIMIT 1",
                String.class, TENANT);
        List<EmbeddingMatch<TextSegment>> ownHits = search(ownStore, model.embed(ownChunkText).content());
        assertThat(ownHits).isNotEmpty();
        assertThat(ownHits.get(0).embedded().text()).isEqualTo(ownChunkText);
        assertThat(ownHits.get(0).embedded().metadata().getString("doc_name")).isEqualTo(DOC);

        // 场景 2（验收）/ SC-005：以对方专有片段查询本方表检索为空；本方表 metadata 无对方文档
        String peerChunkText = jdbcTemplate.queryForObject(
                "SELECT content FROM document_chunk c JOIN document d ON d.id = c.document_id "
                        + "WHERE d.tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) ORDER BY c.seq_index LIMIT 1",
                String.class, PEER);
        assertThat(search(ownStore, model.embed(peerChunkText).content()))
                .as("本方库检索不到对方租户片段（跨租户 100% 隔离）")
                .isEmpty();
        Long peerRowsInOwnTable = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + OWN_TABLE + " WHERE metadata->>'doc_name' = ?", Long.class, PEER_DOC);
        assertThat(peerRowsInOwnTable).isZero();

        // 对方检索本方查不到的内容：命中本方查不到，对方正常命中
        assertThat(search(peerStore, model.embed(peerChunkText).content())).isNotEmpty();
    }

    @Test
    void scenario3_shortDocumentProducesSingleChunk() {
        IngestOutcome outcome = ingestionService.ingest(
                new IngestRequest(TENANT, "short-doc", null, IngestionTestDocuments.shortChineseDocument()));

        // 场景 3（验收）：短文档产生恰好一个片段，不报错
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.INGESTED);
        assertThat(outcome.runs().get(0).chunkCount()).isEqualTo(1);
        Long vectorRows = jdbcTemplate.queryForObject("SELECT count(*) FROM " + OWN_TABLE, Long.class);
        assertThat(vectorRows).isEqualTo(1L);
    }

    private static List<EmbeddingMatch<TextSegment>> search(EmbeddingStore<TextSegment> store, Embedding query) {
        return store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(query)
                        .maxResults(10)
                        .minScore(0.99)
                        .build())
                .matches();
    }

    private static Set<String> metadataKeys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}

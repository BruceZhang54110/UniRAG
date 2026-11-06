package com.unirag.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1 验收（spec.md US1 场景 1/2，FR-001/002/003/004，SC-001/002/004）：
 * 多级中文标题公文的章节对齐断言（任一 chunk 文本不含标题行）、section_path 最深层级、
 * metadata 键集=四键+section_path+section_N、超大章节多 PARENT；
 * 同租户 RECURSIVE/PARENT_CHILD 管线零回归对照。
 * 租户码全局唯一：{@code saUS1}。
 */
class StructureAwareIngestionIT extends AbstractIngestionIT {

    private static final String TENANT = "saUS1";
    private static final String SA_TABLE = "embeddings_saus1_sa";
    private static final String PC_TABLE = "embeddings_saus1_pc";
    private static final String SINGLE_TABLE = "embeddings_saus1_single";
    private static final String DOC = "sa-doc";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, SA_TABLE, PC_TABLE, SINGLE_TABLE);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "saStore", SA_TABLE, 1024);
        insertStoreConfig(tenantId, "pcStore", PC_TABLE, 1024);
        insertStoreConfig(tenantId, "singleStore", SINGLE_TABLE, 1024);
        insertStructureAwarePipeline(tenantId, "sa", 300, 30, 400, "saStore", "default");
        insertParentChildPipeline(tenantId, "pc", 300, 30, 1200, "pcStore", "default");
        insertPipeline(tenantId, "single", 500, 50, "singleStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("saStore", storeBean(TENANT, "saStore"),
                        "pcStore", storeBean(TENANT, "pcStore"),
                        "singleStore", storeBean(TENANT, "singleStore")));
    }

    @Test
    void structureAwareIngestAlignsSectionsAndCarriesPaths() throws Exception {
        IngestOutcome outcome = ingestionService.ingest(
                new IngestRequest(TENANT, DOC, null, IngestionTestDocuments.structureAwareDocument()));
        assertThat(outcome.status()).isEqualTo(IngestOutcome.Status.INGESTED);

        long tenantId = tenantId();
        long documentId = outcome.documentId();
        long saPipeline = pipelineId(tenantId, "sa");

        List<Map<String, Object>> parents = jdbcTemplate.queryForList(
                "SELECT seq_index, section_path, content FROM document_chunk "
                        + "WHERE document_id = ? AND pipeline_id = ? AND chunk_level = 'PARENT' ORDER BY seq_index",
                documentId, saPipeline);
        List<Map<String, Object>> children = jdbcTemplate.queryForList(
                "SELECT seq_index, section_path, content, parent_chunk_id FROM document_chunk "
                        + "WHERE document_id = ? AND pipeline_id = ? AND chunk_level = 'CHILD' ORDER BY seq_index",
                documentId, saPipeline);
        assertThat(parents).isNotEmpty();
        assertThat(children).isNotEmpty();

        // SC-001：任一 chunk 文本不含标题行（边界对齐章节线）
        List<String> headings = List.of("一、总体要求", "（一）基本原则", "（二）发展目标",
                "二、重点任务", "（一）智能编程赋能", "（二）软件产品智能化", "（三）生态协同建设");
        for (Map<String, Object> row : parents) {
            for (String heading : headings) {
                assertThat((String) row.get("content")).as("PARENT 不得含标题行 %s", heading).doesNotContain(heading);
            }
        }
        for (Map<String, Object> row : children) {
            for (String heading : headings) {
                assertThat((String) row.get("content")).as("CHILD 不得含标题行 %s", heading).doesNotContain(heading);
            }
        }

        // SC-002：section_path 覆盖与最深层级（前言为空串，章节为最深路径）
        assertThat(children.stream().map(c -> c.get("section_path")))
                .allSatisfy(p -> assertThat(p).isNotNull());
        long preambleChildren = children.stream()
                .filter(c -> "".equals(c.get("section_path"))).count();
        assertThat(preambleChildren).isGreaterThan(0);
        assertThat(children.stream().filter(c -> ((String) c.get("section_path")).contains("（三）生态协同建设"))
                .count()).isGreaterThan(0);

        // 父子关联完整且父块 section_path 与子块一致
        for (Map<String, Object> child : children) {
            Map<String, Object> parent = jdbcTemplate.queryForMap(
                    "SELECT section_path FROM document_chunk WHERE id = ?", child.get("parent_chunk_id"));
            assertThat(parent.get("section_path")).isEqualTo(child.get("section_path"));
        }

        // metadata 键集：四键 + section_path + section_N；section_2 = 二级累积路径
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + SA_TABLE, Long.class))
                .isEqualTo((long) children.size());
        for (Map<String, Object> child : children) {
            String embeddingId = jdbcTemplate.queryForObject(
                    "SELECT embedding_id FROM document_chunk WHERE document_id = ? AND pipeline_id = ? "
                            + "AND chunk_level = 'CHILD' AND seq_index = ?",
                    String.class, documentId, saPipeline, child.get("seq_index"));
            String metadataJson = jdbcTemplate.queryForObject(
                    "SELECT metadata FROM " + SA_TABLE + " WHERE embedding_id = ?::uuid", String.class, embeddingId);
            JsonNode metadata = OBJECT_MAPPER.readTree(metadataJson);
            Set<String> keys = new HashSet<>();
            metadata.fieldNames().forEachRemaining(keys::add);
            String path = (String) child.get("section_path");
            if (path.isEmpty()) {
                assertThat(keys).containsExactlyInAnyOrder(
                        "doc_name", "document_id", "pipeline_key", "chunk_index", "section_path");
                assertThat(metadata.get("section_path").asText()).isEmpty();
            } else {
                int depth = path.split("/").length;
                assertThat(keys).contains("section_path", "section_1");
                assertThat(metadata.get("section_" + depth).asText()).isEqualTo(path);
                assertThat(metadata.get("section_1").asText()).isEqualTo(path.split("/")[0]);
                assertThat(keys).allSatisfy(k -> assertThat(k).matches(
                        "doc_name|document_id|pipeline_key|chunk_index|section_path|section_[1-6]"));
            }
        }

        // 零回归：RECURSIVE/PARENT_CHILD 产出无 section_path
        Map<String, RunOutcome> runsByKey = outcome.runs().stream()
                .collect(Collectors.toMap(RunOutcome::pipelineKey, Function.identity()));
        assertThat(runsByKey.keySet()).containsExactlyInAnyOrder("sa", "pc", "single");
        Long pcParents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND pipeline_id = ? AND chunk_level='PARENT'",
                Long.class, documentId, pipelineId(tenantId, "pc"));
        assertThat(pcParents).isGreaterThan(0);
        Long othersWithSection = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND pipeline_id IN (?, ?) "
                        + "AND section_path IS NOT NULL",
                Long.class, documentId, pipelineId(tenantId, "pc"), pipelineId(tenantId, "single"));
        assertThat(othersWithSection).isZero();
    }

    private long tenantId() {
        return jdbcTemplate.queryForObject("SELECT id FROM tenant WHERE tenant_code = ?", Long.class, TENANT);
    }

    private long pipelineId(long tenantId, String key) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ingestion_pipeline WHERE tenant_id = ? AND pipeline_key = ?",
                Long.class, tenantId, key);
    }
}

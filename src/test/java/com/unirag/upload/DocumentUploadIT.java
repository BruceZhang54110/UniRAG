package com.unirag.upload;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.upload.support.TenantContextInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US1 验收（spec.md US1，FR-001/002/005，SC-001/SC-004/SC-005）：
 * 一次性上传长 Markdown → 原文落 document_blob（OpenDAL 读回字节一致）→ 005 摄取管线切片向量化
 * （chunk 行数与向量表一致）→ 响应含逐管线 outcome；同内容二次上传 NOOP 不翻倍；
 * 对端租户上传同名文档完全隔离。
 * 租户码全局唯一：{@code upUS1} / {@code upUS1Peer}。
 */
class DocumentUploadIT extends AbstractUploadIT {

    private static final String TENANT = "upUS1";
    private static final String PEER = "upUS1Peer";
    private static final String TABLE = "embeddings_upus1";
    private static final String PEER_TABLE = "embeddings_upus1peer";
    private static final String DOC = "upload-doc-a";
    private static final String FILE_NAME = "手册A.md";

    @BeforeEach
    void setUp() {
        cleanupUploadTenant(TENANT, TABLE);
        cleanupUploadTenant(PEER, PEER_TABLE);
        prepareTenant(TENANT, TABLE);
        prepareTenant(PEER, PEER_TABLE);
    }

    private void prepareTenant(String tenant, String table) {
        long tenantId = insertTenant(tenant);
        insertStoreConfig(tenantId, "default", table, 1024);
        insertPipeline(tenantId, "main", 500, 50, "default", "default");
        registrar.loadAndRegister();
        stubRouter(tenant,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("default", storeBean(tenant, "default")));
    }

    @Test
    void scenario1_uploadIngestsAndStoresFile() throws Exception {
        byte[] bytes = IngestionTestDocuments.longChineseDocument().getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown", bytes))
                        .param("docName", DOC)
                        .param("title", "手册A")
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docName").value(DOC))
                .andExpect(jsonPath("$.data.status").value("INGESTED"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.fileSizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.data.pipelines[0].pipelineKey").value("main"))
                .andExpect(jsonPath("$.data.pipelines[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.pipelines[0].chunks").isNumber());

        // 元数据与摄取（005 口径）：document 行、chunk 行数与向量表一致
        Map<String, Object> document = jdbcTemplate.queryForMap(
                "SELECT id, status, chunk_count, version FROM document WHERE tenant_id = "
                        + "(SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?", TENANT, DOC);
        long documentId = ((Number) document.get("id")).longValue();
        assertThat(document.get("status")).isEqualTo("INGESTED");
        long chunkRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ?", Long.class, documentId);
        long vectorRows = jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);
        assertThat(chunkRows).isEqualTo(vectorRows).isPositive();

        // 原文存储：元数据行 + blob 字节一致（SC-005）
        Map<String, Object> fileRow = jdbcTemplate.queryForMap(
                "SELECT storage_path, file_name, mime_type, size_bytes FROM document_file WHERE document_id = ?", documentId);
        String storagePath = (String) fileRow.get("storage_path");
        assertThat(storagePath).isEqualTo("document_file/" + TENANT + "/" + DOC + "/v1");
        assertThat(fileRow.get("file_name")).isEqualTo(FILE_NAME);
        assertThat(((Number) fileRow.get("size_bytes")).longValue()).isEqualTo(bytes.length);
        assertThat(blobBytes(storagePath)).isEqualTo(bytes);
    }

    @Test
    void scenario2_repeatUploadSameContentIsNoop() throws Exception {
        byte[] bytes = IngestionTestDocuments.longChineseDocument().getBytes(StandardCharsets.UTF_8);
        upload(TENANT, DOC, bytes);

        Map<String, Object> before = jdbcTemplate.queryForMap(
                "SELECT id, version, chunk_count FROM document WHERE tenant_id = "
                        + "(SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?", TENANT, DOC);
        long vectorsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown", bytes))
                        .param("docName", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOOP"))
                .andExpect(jsonPath("$.data.version").value(((Number) before.get("version")).intValue()));

        // FR-005/SC-004：不翻倍
        Long chunksAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ?",
                Long.class, ((Number) before.get("id")).longValue());
        assertThat(chunksAfter).isEqualTo(((Number) before.get("chunk_count")).longValue());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE, Long.class)).isEqualTo(vectorsBefore);
    }

    @Test
    void scenario3_peerTenantUploadsSameDocNameIsolated() throws Exception {
        byte[] bytes = IngestionTestDocuments.longChineseDocument().getBytes(StandardCharsets.UTF_8);
        upload(TENANT, DOC, bytes);
        long vectorsBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);

        upload(PEER, DOC, bytes);

        // 两租户各自成行：对端向量落对端表，本表行数不动（租户隔离 0 泄漏）
        long peerVectors = jdbcTemplate.queryForObject("SELECT count(*) FROM " + PEER_TABLE, Long.class);
        assertThat(peerVectors).isEqualTo(vectorsBefore).isPositive();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE, Long.class)).isEqualTo(vectorsBefore);
        // 原文键带租户前缀，互不可见
        assertThat(blobExists("document_file/" + PEER + "/" + DOC + "/v1")).isTrue();
    }

    @Test
    void scenario4_missingOrUnknownTenantRejected() throws Exception {
        byte[] bytes = IngestionTestDocuments.shortChineseDocument().getBytes(StandardCharsets.UTF_8);
        // 缺租户头 → 400
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown", bytes))
                        .param("docName", DOC))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TENANT_CODE_REQUIRED"));
        // 未知租户 → 404，且无残留
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown", bytes))
                        .param("docName", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, "no-such-tenant"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TENANT_NOT_FOUND"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document WHERE doc_name = ?", Long.class, DOC)).isZero();
    }

    private void upload(String tenant, String docName, byte[] bytes) throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown", bytes))
                        .param("docName", docName)
                        .header(TenantContextInterceptor.TENANT_HEADER, tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INGESTED"));
    }
}

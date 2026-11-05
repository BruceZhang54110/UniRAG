package com.unirag.upload;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.common.tenant.TenantContextInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存储 schema 终态验收（spec FR-001/FR-006/FR-017，SC-002，contracts §7）：
 * - document_blob 已删除（to_regclass 为空）；
 * - upload_chunk 含 storage_path、无 data 列；全库不再有任何 bytea 列；
 * - 50 MiB 上传后关系数据库只新增元数据行（文件字节增量为 0）。
 * 租户码全局唯一：{@code upSchema}。
 */
class StorageSchemaIT extends AbstractUploadIT {

    private static final String TENANT = "upSchema";
    private static final String TABLE = "embeddings_upschema";
    private static final String DOC = "schema-doc";

    @BeforeEach
    void setUp() {
        cleanupUploadTenant(TENANT, TABLE);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "default", TABLE, 1024);
        insertPipeline(tenantId, "main", 500, 50, "default", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("default", storeBean(TENANT, "default")));
    }

    @Test
    void legacyBlobStorageIsFullyRemoved() {
        Object blobTable = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.document_blob')", Object.class);
        assertThat(blobTable).isNull();

        List<String> chunkColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'upload_chunk'", String.class);
        assertThat(chunkColumns).contains("storage_path", "size_bytes", "session_id", "seq", "created_at")
                .doesNotContain("data");

        // 全库不再有 bytea 列：文件字节已彻底离开数据库（FR-001/FR-006）
        List<String> byteaColumns = jdbcTemplate.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND data_type = 'bytea'", String.class);
        assertThat(byteaColumns).isEmpty();
    }

    @Test
    void largeUploadLeavesZeroFileBytesInDatabase() throws Exception {
        // 50 MiB 内容：一次性通道上限 10 MiB，按分块上传（5 MiB × 9 + 尾块）
        byte[] bytes = largeText(50 * 1024 * 1024 - 128);
        final int chunkSize = 5 * 1024 * 1024;
        int totalChunks = (bytes.length + chunkSize - 1) / chunkSize;
        long uploadId = initSession(totalChunks, chunkSize, bytes.length, sha256(bytes));

        for (int seq = 0; seq < totalChunks; seq++) {
            putChunk(uploadId, seq, chunkOf(bytes, seq, chunkSize));
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/uploads/{id}/complete", uploadId)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INGESTED"));

        // 仅元数据行：document_file 登记大小，数据库内无任何字节列可存内容（SC-002）
        Map<String, Object> fileRow = jdbcTemplate.queryForMap(
                "SELECT df.size_bytes, df.storage_path FROM document_file df "
                        + "JOIN document d ON d.id = df.document_id "
                        + "WHERE d.tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND d.doc_name = ?",
                TENANT, DOC);
        assertThat(((Number) fileRow.get("size_bytes")).longValue()).isEqualTo(bytes.length);
        assertThat(blobBytes((String) fileRow.get("storage_path"))).isEqualTo(bytes);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM upload_chunk uc JOIN upload_session us ON us.id = uc.session_id "
                        + "WHERE us.tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                Long.class, TENANT)).isZero();
    }

    private long initSession(int totalChunks, int chunkSize, long totalSize, String sha256) throws Exception {
        String response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/uploads")
                        .contentType("application/json")
                        .content("""
                                {"docName":"%s","title":"t","totalSize":%d,"chunkSize":%d,
                                 "totalChunks":%d,"contentSha256":"%s"}
                                """.formatted(DOC, totalSize, chunkSize, totalChunks, sha256))
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.uploadId")).longValue();
    }

    private void putChunk(long uploadId, int seq, byte[] chunk) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/uploads/{id}/chunks/{seq}", uploadId, seq)
                        .contentType("application/octet-stream")
                        .content(chunk)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk());
    }

    private byte[] chunkOf(byte[] content, int seq, int chunkSize) {
        int from = seq * chunkSize;
        int to = Math.min(from + chunkSize, content.length);
        byte[] chunk = new byte[to - from];
        System.arraycopy(content, from, chunk, 0, to - from);
        return chunk;
    }

    private String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private byte[] largeText(int size) {
        String line = "UniRAG schema validation line with enough ascii text to fill the buffer.\n";
        StringBuilder sb = new StringBuilder(size);
        while (sb.length() < size) {
            sb.append(line);
        }
        sb.setLength(size);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}

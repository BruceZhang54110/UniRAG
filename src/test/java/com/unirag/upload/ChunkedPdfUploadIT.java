package com.unirag.upload;

import com.unirag.common.tenant.TenantContextInterceptor;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.support.BinaryTestDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 022 US1 验收（spec.md US1 场景 4，FR-006；research R5）：分块上传携带原始文件名完成入库。
 * 初始化带 fileName=manual.pdf → 版本对象 file_name/mime 为 PDF 且走抽取；
 * 缺省 fileName → 回退 {docName}.txt 保持旧行为。
 * 租户码全局唯一：{@code pdfCk}。
 */
class ChunkedPdfUploadIT extends AbstractUploadIT {

    private static final String TENANT = "pdfCk";
    private static final String TABLE = "embeddings_pdfck";
    private static final String DOC = "pdf-chunked-doc";
    private static final int CHUNK_SIZE = 1024;

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
    void chunkedPdfWithFileNameCompletesAndIngestsAsPdf() throws Exception {
        byte[] pdf = BinaryTestDocuments.pdfWithText(
                "General Requirements",
                "Chunked upload keeps the original file name through the whole session.",
                "The assembler must pass it to finalize instead of a hard-coded txt name.");
        long uploadId = initSession(TENANT, DOC, pdf, "manual.pdf");

        for (int seq = 0; seq < totalChunks(pdf.length); seq++) {
            putChunk(TENANT, uploadId, seq, chunkOf(pdf, seq));
        }
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", uploadId)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INGESTED"));

        long documentId = documentId();
        Map<String, Object> fileRow = jdbcTemplate.queryForMap(
                "SELECT file_name, mime_type, size_bytes FROM document_file WHERE document_id = ?", documentId);
        assertThat(fileRow.get("file_name")).isEqualTo("manual.pdf");
        assertThat(fileRow.get("mime_type")).isEqualTo("application/pdf");
        assertThat(((Number) fileRow.get("size_bytes")).longValue()).isEqualTo(pdf.length);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND content LIKE ?",
                Long.class, documentId, "%General Requirements%")).isPositive();
    }

    @Test
    void chunkedWithoutFileNameFallsBackToTxt() throws Exception {
        String text = "UniRAG chunked fallback document. " + "fallback ".repeat(200);
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        long uploadId = initSession(TENANT, DOC, content, null);

        for (int seq = 0; seq < totalChunks(content.length); seq++) {
            putChunk(TENANT, uploadId, seq, chunkOf(content, seq));
        }
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", uploadId)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INGESTED"));

        Map<String, Object> fileRow = jdbcTemplate.queryForMap(
                "SELECT file_name, mime_type FROM document_file WHERE document_id = ?", documentId());
        assertThat(fileRow.get("file_name")).isEqualTo(DOC + ".txt");
        assertThat(fileRow.get("mime_type")).isEqualTo("text/plain");
    }

    private long initSession(String tenant, String docName, byte[] content, String fileName) throws Exception {
        String fileNameField = fileName == null ? "" : ",\"fileName\":\"%s\"".formatted(fileName);
        String response = mockMvc.perform(post("/api/v1/uploads")
                        .contentType("application/json")
                        .content("""
                                {"docName":"%s","title":"t","totalSize":%d,"chunkSize":%d,
                                 "totalChunks":%d,"contentSha256":"%s"%s}
                                """.formatted(docName, content.length, CHUNK_SIZE,
                                totalChunks(content.length), sha256Hex(content), fileNameField))
                        .header(TenantContextInterceptor.TENANT_HEADER, tenant))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.uploadId")).longValue();
    }

    private void putChunk(String tenant, long uploadId, int seq, byte[] chunk) throws Exception {
        mockMvc.perform(put("/api/v1/uploads/{id}/chunks/{seq}", uploadId, seq)
                        .contentType("application/octet-stream")
                        .content(chunk)
                        .header(TenantContextInterceptor.TENANT_HEADER, tenant))
                .andExpect(status().isOk());
    }

    private int totalChunks(int size) {
        return (size + CHUNK_SIZE - 1) / CHUNK_SIZE;
    }

    private byte[] chunkOf(byte[] content, int seq) {
        int from = seq * CHUNK_SIZE;
        int to = Math.min(from + CHUNK_SIZE, content.length);
        byte[] chunk = new byte[to - from];
        System.arraycopy(content, from, chunk, 0, to - from);
        return chunk;
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private long documentId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?",
                Long.class, TENANT, DOC);
    }
}

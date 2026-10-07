package com.unirag.upload;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.upload.support.TenantContextInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 断点续传验收（spec FR-010，SC-007，research R4）：
 * 8 块会话只传一半 → complete 409 列缺失块；补传后 complete 200 且原文逐字节一致；
 * 同 seq 重传幂等覆盖；SHA-256 不符 409 CHECKSUM_MISMATCH；跨租户访问会话 404。
 * 租户码全局唯一：{@code upResume} / {@code upResumePeer}。
 */
class ChunkedUploadResumeIT extends AbstractUploadIT {

    private static final String TENANT = "upResume";
    private static final String PEER = "upResumePeer";
    private static final String TABLE = "embeddings_upresume";
    private static final String DOC = "chunked-doc";
    private static final int CHUNK_SIZE = 1024;
    private static final int TOTAL_CHUNKS = 8;

    @BeforeEach
    void setUp() {
        cleanupUploadTenant(TENANT, TABLE);
        cleanupUploadTenant(PEER);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "default", TABLE, 1024);
        insertPipeline(tenantId, "main", 500, 50, "default", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("default", storeBean(TENANT, "default")));
        insertTenant(PEER);
    }

    @Test
    void scenario1_resumeAfterInterruptionCompletesByteIdentical() throws Exception {
        byte[] content = chunkableContent();
        long uploadId = initSession(TENANT, DOC, content);

        // 只传 0-3 块 → complete 409 列缺失块
        for (int seq = 0; seq < 4; seq++) {
            putChunk(TENANT, uploadId, seq, chunkOf(content, seq));
        }
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", uploadId)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_INCOMPLETE"))
                .andExpect(jsonPath("$.error.details.missingChunks")
                        .value(org.hamcrest.Matchers.contains(4, 5, 6, 7)));

        // 断点续传：补传 4-7 → complete 200，原文逐字节一致（SC-007）
        for (int seq = 4; seq < 8; seq++) {
            putChunk(TENANT, uploadId, seq, chunkOf(content, seq));
        }
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", uploadId)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INGESTED"))
                .andExpect(jsonPath("$.data.docName").value(DOC));

        String storagePath = jdbcTemplate.queryForObject(
                "SELECT df.storage_path FROM document_file df JOIN document d ON d.id = df.document_id "
                        + "WHERE d.doc_name = ? AND d.tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                String.class, DOC, TENANT);
        assertThat(blobBytes(storagePath)).isEqualTo(content);
        // 暂存块已清除
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM upload_chunk WHERE session_id = ?", Long.class, uploadId)).isZero();
    }

    @Test
    void scenario2_sameSeqRetransmitIsIdempotentOverwrite() throws Exception {
        byte[] content = chunkableContent();
        byte[] replacement = " ".repeat(CHUNK_SIZE).getBytes(StandardCharsets.UTF_8);
        System.arraycopy("REPLACED-HEADER".getBytes(StandardCharsets.UTF_8), 0,
                replacement, 0, "REPLACED-HEADER".getBytes(StandardCharsets.UTF_8).length);
        // 最终内容 = 原文 + 第 2 块被覆盖；摘要按最终内容声明
        byte[] modified = content.clone();
        System.arraycopy(replacement, 0, modified, 2 * CHUNK_SIZE, CHUNK_SIZE);

        long uploadId = initSession(TENANT, DOC, modified);
        putChunk(TENANT, uploadId, 2, chunkOf(content, 2));
        putChunk(TENANT, uploadId, 2, replacement);   // 同 seq 重传 = 覆盖
        for (int seq = 0; seq < TOTAL_CHUNKS; seq++) {
            if (seq != 2) {
                putChunk(TENANT, uploadId, seq, chunkOf(content, seq));
            }
        }
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", uploadId)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk());

        // 最终内容以最后一次写入为准
        String storagePath = jdbcTemplate.queryForObject(
                "SELECT df.storage_path FROM document_file df JOIN document d ON d.id = df.document_id "
                        + "WHERE d.doc_name = ? AND d.tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                String.class, DOC, TENANT);
        byte[] stored = blobBytes(storagePath);
        assertThat(new String(stored, 2 * CHUNK_SIZE, 15, StandardCharsets.UTF_8)).isEqualTo("REPLACED-HEADER");
        assertThat(stored).isEqualTo(modified);
    }

    @Test
    void scenario3_checksumMismatchRejected() throws Exception {
        byte[] content = chunkableContent();
        long uploadId = initSession(TENANT, DOC, content,
                "0".repeat(64));   // 伪造全文摘要
        for (int seq = 0; seq < TOTAL_CHUNKS; seq++) {
            putChunk(TENANT, uploadId, seq, chunkOf(content, seq));
        }
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", uploadId)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHECKSUM_MISMATCH"));
    }

    @Test
    void scenario4_crossTenantSessionNotVisible() throws Exception {
        byte[] content = chunkableContent();
        long uploadId = initSession(TENANT, DOC, content);

        // 对端租户初始化请求体非法（缺参数）之外，核心是会话不可见
        mockMvc.perform(put("/api/v1/uploads/{id}/chunks/{seq}", uploadId, 0)
                        .contentType("application/octet-stream")
                        .content(chunkOf(content, 0))
                        .header(TenantContextInterceptor.TENANT_HEADER, PEER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/uploads/{id}/complete", uploadId)
                        .header(TenantContextInterceptor.TENANT_HEADER, PEER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_NOT_FOUND"));
    }

    /** 8×1024 可切块的文本内容（确保 UTF-8 多字节不在块边界处切坏——用 ASCII 填充）。 */
    private byte[] chunkableContent() {
        StringBuilder sb = new StringBuilder(CHUNK_SIZE * TOTAL_CHUNKS);
        while (sb.length() < CHUNK_SIZE * TOTAL_CHUNKS) {
            sb.append("UniRAG chunk-upload line with enough ascii text to split safely.\n");
        }
        return sb.substring(0, CHUNK_SIZE * TOTAL_CHUNKS).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] chunkOf(byte[] content, int seq) {
        int from = seq * CHUNK_SIZE;
        int to = Math.min(from + CHUNK_SIZE, content.length);
        byte[] chunk = new byte[to - from];
        System.arraycopy(content, from, chunk, 0, to - from);
        return chunk;
    }

    private long initSession(String tenant, String docName, byte[] content) throws Exception {
        return initSession(tenant, docName, content, sha256Hex(content));
    }

    private long initSession(String tenant, String docName, byte[] content, String sha256) throws Exception {
        String response = mockMvc.perform(post("/api/v1/uploads")
                        .contentType("application/json")
                        .content("""
                                {"docName":"%s","title":"t","totalSize":%d,"chunkSize":%d,
                                 "totalChunks":%d,"contentSha256":"%s"}
                                """.formatted(docName, content.length, CHUNK_SIZE, TOTAL_CHUNKS, sha256))
                        .header(TenantContextInterceptor.TENANT_HEADER, tenant))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.receivedChunks").isEmpty())
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

    private String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

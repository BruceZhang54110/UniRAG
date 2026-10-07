package com.unirag.upload;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.upload.support.TenantContextInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US2 验收（spec.md US2，FR-004；SC-003/SC-005）：
 * 上传 → 下载原文逐字节一致（Content-Type = 登记 mime、Content-Disposition filename* 非 ASCII 可编码）；
 * 跨租户下载/详情 → 404 DOCUMENT_NOT_FOUND（不泄漏存在性）；列表仅本租户、按创建时间倒序、分页 total/items 正确；
 * 详情字段与 document/document_file 实际入库一致。
 * 租户码全局唯一：{@code upDl} / {@code upDlPeer}。
 */
class DocumentDownloadIT extends AbstractUploadIT {

    private static final String TENANT = "upDl";
    private static final String PEER = "upDlPeer";
    private static final String TABLE = "embeddings_updl";
    private static final String PEER_TABLE = "embeddings_updlpeer";

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
    void scenario1_downloadRoundTripByteIdentical() throws Exception {
        String fileName = "手册A.md";
        byte[] bytes = IngestionTestDocuments.longChineseDocument().getBytes(StandardCharsets.UTF_8);
        upload(TENANT, "doc-a", fileName, "text/markdown", bytes);

        MockHttpServletResponse response = mockMvc.perform(
                        get("/api/v1/documents/{docName}/file", "doc-a")
                                .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/markdown"))
                .andReturn().getResponse();

        // SC-005：字节逐字节一致 + filename* 为 UTF-8 percent 编码（RFC 5987）
        assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename*=UTF-8''" + encoded);
    }

    @Test
    void scenario2_crossTenantAccessIsNotFoundWithoutLeakage() throws Exception {
        byte[] bytes = IngestionTestDocuments.longChineseDocument().getBytes(StandardCharsets.UTF_8);
        upload(TENANT, "doc-a", "手册A.md", "text/markdown", bytes);

        // 跨租户下载/详情：404 DOCUMENT_NOT_FOUND，与"不存在"不可区分（spec US2-2）
        mockMvc.perform(get("/api/v1/documents/{docName}/file", "doc-a")
                        .header(TenantContextInterceptor.TENANT_HEADER, PEER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/documents/{docName}", "doc-a")
                        .header(TenantContextInterceptor.TENANT_HEADER, PEER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
        // 本租户访问不存在的文档同样 404
        mockMvc.perform(get("/api/v1/documents/{docName}", "no-such-doc")
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("DOCUMENT_NOT_FOUND"));
        // 对端租户列表为空
        mockMvc.perform(get("/api/v1/documents")
                        .header(TenantContextInterceptor.TENANT_HEADER, PEER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void scenario3_listPagedDescAndTenantScoped() throws Exception {
        upload(TENANT, "doc-a", "a.md", "text/markdown", docBytes("甲"));
        upload(TENANT, "doc-b", "b.md", "text/markdown", docBytes("乙"));
        upload(TENANT, "doc-c", "c.md", "text/markdown", docBytes("丙"));

        // 第一页：倒序（最新在前），total 如实
        mockMvc.perform(get("/api/v1/documents").param("page", "1").param("size", "2")
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].docName").value("doc-c"))
                .andExpect(jsonPath("$.data.items[1].docName").value("doc-b"))
                .andExpect(jsonPath("$.data.items[0].status").value("INGESTED"))
                .andExpect(jsonPath("$.data.items[0].version").value(1))
                .andExpect(jsonPath("$.data.items[0].mimeType").value("text/markdown"))
                .andExpect(jsonPath("$.data.items[0].fileSizeBytes").isNumber())
                .andExpect(jsonPath("$.data.items[0].createdAt").isNumber());
        // 第二页：仅剩最早的 doc-a
        mockMvc.perform(get("/api/v1/documents").param("page", "2").param("size", "2")
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].docName").value("doc-a"));
    }

    @Test
    void scenario4_detailMatchesPersistedRows() throws Exception {
        String fileName = "notes.txt";
        byte[] bytes = (IngestionTestDocuments.shortChineseDocument() + "\nplain text body").getBytes(StandardCharsets.UTF_8);
        upload(TENANT, "doc-txt", fileName, "text/plain", bytes);

        Map<String, Object> document = jdbcTemplate.queryForMap(
                "SELECT id, status, chunk_count, version FROM document WHERE tenant_id = "
                        + "(SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?", TENANT, "doc-txt");
        Map<String, Object> fileRow = jdbcTemplate.queryForMap(
                "SELECT file_name, mime_type, size_bytes FROM document_file WHERE document_id = ?",
                ((Number) document.get("id")).longValue());

        mockMvc.perform(get("/api/v1/documents/{docName}", "doc-txt")
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docName").value("doc-txt"))
                .andExpect(jsonPath("$.data.status").value((String) document.get("status")))
                .andExpect(jsonPath("$.data.chunkCount").value(((Number) document.get("chunk_count")).intValue()))
                .andExpect(jsonPath("$.data.version").value(((Number) document.get("version")).intValue()))
                .andExpect(jsonPath("$.data.fileSizeBytes").value(((Number) fileRow.get("size_bytes")).longValue()))
                .andExpect(jsonPath("$.data.mimeType").value(fileRow.get("mime_type")))
                .andExpect(jsonPath("$.data.mimeType").value("text/plain"))
                .andExpect(jsonPath("$.data.createdAt").isNumber())
                .andExpect(jsonPath("$.data.updatedAt").isNumber())
                .andExpect(jsonPath("$.data.source").value("upload"));
    }

    private byte[] docBytes(String marker) {
        return (IngestionTestDocuments.longChineseDocument() + "\n\n## 分档标记 " + marker).getBytes(StandardCharsets.UTF_8);
    }

    private void upload(String tenant, String docName, String fileName, String mimeType, byte[] bytes) throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", fileName, mimeType, bytes))
                        .param("docName", docName)
                        .header(TenantContextInterceptor.TENANT_HEADER, tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INGESTED"));
    }
}

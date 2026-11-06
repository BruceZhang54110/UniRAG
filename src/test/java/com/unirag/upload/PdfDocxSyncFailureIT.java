package com.unirag.upload;

import com.unirag.common.tenant.TenantContextInterceptor;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.support.BinaryTestDocuments;
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
 * 022 US2 验收（spec.md US2，FR-002/FR-004/FR-009，SC-002/SC-003）：
 * 同步路径失败兜底——损坏 PDF 请求级失败且原文对象被补偿删除；UTF-8 文本改名 .pdf
 * 被魔数嗅探拦截；错误响应不含内部细节（宪法 V）。租户码全局唯一：{@code pdfSy}。
 */
class PdfDocxSyncFailureIT extends AbstractUploadIT {

    private static final String TENANT = "pdfSy";
    private static final String TABLE = "embeddings_pdfsy";
    private static final String DOC = "pdf-sync-failure-doc";

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
    void corruptedPdfSyncUploadFailsAndCleansUpBlob() throws Exception {
        byte[] corrupted = BinaryTestDocuments.truncatedPdf();

        String body = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "损坏.pdf", "application/pdf", corrupted))
                        .param("docName", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PARSE_FAILED"))
                .andReturn().getResponse().getContentAsString();

        // FR-009：响应不含内部细节
        assertThat(body).doesNotContain("Tika", "org.apache", "stackTrace", "Exception");

        // 零残留：无文档行、无原文对象（finalizeUploadResult 补偿删除生效）
        Long documentRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?",
                Long.class, TENANT, DOC);
        assertThat(documentRows).isZero();
        assertThat(blobExists("document_file/" + TENANT + "/" + DOC + "/v1")).isFalse();
    }

    @Test
    void utf8TextRenamedToPdfRejectedByMagicSniffing() throws Exception {
        byte[] textBytes = "一、总体要求".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "伪装.pdf", "application/pdf", textBytes))
                        .param("docName", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?",
                Long.class, TENANT, DOC)).isZero();
    }

    @Test
    void unsupportedExtensionStillRejected() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "数据.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                BinaryTestDocuments.xlsxBytes()))
                        .param("docName", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));
    }
}

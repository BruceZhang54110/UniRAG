package com.unirag.upload;

import com.unirag.common.tenant.TenantContextInterceptor;
import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.ingestion.support.BinaryTestDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 022 US1 验收（spec.md US1，FR-001/003/005，SC-001/SC-005）：
 * 同步一次性上传 PDF/DOCX → 抽取文本走既有切分管线 → 原文字节原样落 RustFS。
 * DOCX 用中文公文语料（结构感知章节断言）；PDF 用 ASCII 语料（内嵌字体限制），
 * 断言抽取文字进入切片、原文字节/文件名/mime 落版本对象且下载字节一致。
 * 租户码全局唯一：{@code pdfUS1}。
 */
class PdfDocxUploadIT extends AbstractUploadIT {

    private static final String TENANT = "pdfUS1";
    private static final String TABLE = "embeddings_pdfus1";
    private static final String DOC = "pdf-doc-a";

    @BeforeEach
    void setUp() {
        cleanupUploadTenant(TENANT, TABLE);
        prepareTenant();
    }

    private void prepareTenant() {
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "default", TABLE, 1024);
        insertPipeline(tenantId, "main", 500, 50, "default", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT,
                Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("default", storeBean(TENANT, "default")));
    }

    @Test
    void docxUploadIngestsChineseChaptersAndStoresOriginalBytes() throws Exception {
        String document = IngestionTestDocuments.structureAwareDocument();
        byte[] docx = BinaryTestDocuments.docxWithText(document);

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "公文.docx",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx))
                        .param("docName", DOC)
                        .param("title", "公文")
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docName").value(DOC))
                .andExpect(jsonPath("$.data.status").value("INGESTED"));

        long documentId = documentId();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM document WHERE id = ?", String.class, documentId)).isEqualTo("INGESTED");
        long chunkRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ?", Long.class, documentId);
        long vectorRows = jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE, Long.class);
        assertThat(chunkRows).isEqualTo(vectorRows).isPositive();

        // 抽取文本（非原文字节）进入切片：章节文字可命中（SC-001）
        Long hits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND content LIKE ?",
                Long.class, documentId, "%一、总体要求%");
        assertThat(hits).isPositive();

        // 原文对象：字节、文件名、mime 均为原始 DOCX（FR-005）
        Map<String, Object> fileRow = jdbcTemplate.queryForMap(
                "SELECT storage_path, file_name, mime_type, size_bytes FROM document_file WHERE document_id = ?",
                documentId);
        assertThat(fileRow.get("file_name")).isEqualTo("公文.docx");
        assertThat(fileRow.get("mime_type"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(((Number) fileRow.get("size_bytes")).longValue()).isEqualTo(docx.length);
        assertThat(blobBytes((String) fileRow.get("storage_path"))).isEqualTo(docx);
    }

    @Test
    void pdfUploadIngestsExtractedTextAndStoresOriginalBytes() throws Exception {
        byte[] pdf = BinaryTestDocuments.pdfWithText(
                "General Requirements",
                "Stick to the basic principles and keep the scope unchanged.",
                "Second section with enough words to survive tokenization into chunks.");

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "manual.pdf", "application/pdf", pdf))
                        .param("docName", DOC)
                        .param("title", "手册")
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INGESTED"));

        long documentId = documentId();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ? AND content LIKE ?",
                Long.class, documentId, "%General Requirements%")).isPositive();

        Map<String, Object> fileRow = jdbcTemplate.queryForMap(
                "SELECT storage_path, file_name, mime_type FROM document_file WHERE document_id = ?",
                documentId);
        assertThat(fileRow.get("file_name")).isEqualTo("manual.pdf");
        assertThat(fileRow.get("mime_type")).isEqualTo("application/pdf");
        assertThat(blobBytes((String) fileRow.get("storage_path"))).isEqualTo(pdf);
    }

    private long documentId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?",
                Long.class, TENANT, DOC);
    }
}

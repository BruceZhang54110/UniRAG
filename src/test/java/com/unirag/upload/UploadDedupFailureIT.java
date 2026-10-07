package com.unirag.upload;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.upload.support.TenantContextInterceptor;
import org.apache.opendal.Operator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class UploadDedupFailureIT extends AbstractUploadIT {

    private static final String TENANT = "upDedup";
    private static final String TABLE = "embeddings_updedup";
    private static final String DOC = "dedup-document";
    private static final String FILE_NAME = "dedup.md";

    @SpyBean(name = "documentStorageOperator")
    private Operator storageOperatorSpy;

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
    void changedContentCreatesNextVersionAndDeletesOldBlob() throws Exception {
        upload(DOC, "first version of the document, with enough content to ingest.");
        assertThat(blobExists(path(1))).isTrue();

        upload(DOC, "second version replaces the first document content and its chunks.");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM document WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?",
                Integer.class, TENANT, DOC)).isEqualTo(2);
        assertThat(blobExists(path(1))).isFalse();
        assertThat(blobExists(path(2))).isTrue();
        long documentId = jdbcTemplate.queryForObject(
                "SELECT id FROM document WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND doc_name = ?",
                Long.class, TENANT, DOC);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_file WHERE document_id = ?", Long.class, documentId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_chunk WHERE document_id = ?", Long.class, documentId)).isPositive();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + TABLE, Long.class)).isPositive();
    }

    @Test
    void invalidExtensionAndBinaryDisguisedAsMarkdownLeaveNoRows() throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "payload.exe", "application/octet-stream", "plain text".getBytes(StandardCharsets.UTF_8)))
                        .param("docName", "invalid-extension")
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "image.md", "text/markdown", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 1}))
                        .param("docName", "disguised-binary")
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FILE_TYPE"));

        assertNoDocumentOrFileRows();
    }

    @Test
    void oversizedOneShotRequestIsRejectedWithoutRows() throws Exception {
        byte[] oversized = new byte[10 * 1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) 'a');
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown", oversized))
                        .param("docName", "oversized")
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
        assertNoDocumentOrFileRows();
    }

    @Test
    void storageWriteFailureReturnsReadableErrorAndLeavesNoRows(CapturedOutput output) throws Exception {
        String failingPath = path(1);
        doThrow(new IllegalStateException("simulated storage outage"))
                .when(storageOperatorSpy).write(eq(failingPath), any(byte[].class));

        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown",
                                "storage failure test content is valid UTF-8 text.".getBytes(StandardCharsets.UTF_8)))
                        .param("docName", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());

        assertNoDocumentOrFileRows();
        assertThat(output.getOut()).doesNotContain("simulated storage outage", "StrongPass123!");
    }

    private void upload(String docName, String content) throws Exception {
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown", content.getBytes(StandardCharsets.UTF_8)))
                        .param("docName", docName)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk());
    }

    private void assertNoDocumentOrFileRows() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                Long.class, TENANT)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_file WHERE document_id IN (SELECT id FROM document WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?))",
                Long.class, TENANT)).isZero();
    }

    private String path(int version) {
        return "document_file/" + TENANT + "/" + DOC + "/v" + version;
    }
}

package com.unirag.upload;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.common.tenant.TenantContextInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对象缺失错误语义验收（spec FR-011，contracts §5）：
 * 元数据存在但 RustFS 对象被外部删除时，下载返回 404 {@code ORIGINAL_FILE_UNAVAILABLE}，
 * 不得返回空内容或伪装成功。
 * 租户码全局唯一：{@code upMiss}。
 */
class DownloadMissingObjectIT extends AbstractUploadIT {

    private static final String TENANT = "upMiss";
    private static final String TABLE = "embeddings_upmiss";
    private static final String DOC = "missing-doc";
    private static final String FILE_NAME = "missing.md";

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
    void downloadReturnsOriginalUnavailableWhenObjectMissing() throws Exception {
        byte[] bytes = "this object will be deleted behind the application's back.".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown", bytes))
                        .param("docName", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk());

        String storagePath = jdbcTemplate.queryForObject(
                "SELECT df.storage_path FROM document_file df JOIN document d ON d.id = df.document_id "
                        + "WHERE d.tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND d.doc_name = ?",
                String.class, TENANT, DOC);
        withStorageRetry(() -> {
            documentStorageOperator.delete(storagePath);
            return null;
        });
        assertThat(blobExists(storagePath)).isFalse();

        mockMvc.perform(get("/api/v1/documents/{docName}/file", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORIGINAL_FILE_UNAVAILABLE"));
    }
}

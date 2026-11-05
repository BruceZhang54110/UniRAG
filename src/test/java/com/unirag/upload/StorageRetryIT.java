package com.unirag.upload;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.common.tenant.TenantContextInterceptor;
import org.apache.opendal.OpenDALException;
import org.apache.opendal.Operator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 存储瞬时错误重试验收（research R4，contracts §3）：
 * RustFS 在 404 关闭连接后，下一次 PUT 可能报 {@code Unexpected ... connection closed}；
 * {@link com.unirag.infrastructure.storage.DocumentStorage} 应对该瞬时错误重试一次并成功。
 * 租户码全局唯一：{@code upRetry}。
 */
class StorageRetryIT extends AbstractUploadIT {

    private static final String TENANT = "upRetry";
    private static final String TABLE = "embeddings_upretry";
    private static final String DOC = "retry-doc";
    private static final String FILE_NAME = "retry.md";

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
    void transientWriteFailureIsRetriedAndUploadSucceeds() throws Exception {
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstCall.getAndSet(false)) {
                throw new OpenDALException(OpenDALException.Code.Unexpected, "simulated connection closed");
            }
            return invocation.callRealMethod();
        }).when(storageOperatorSpy).write(anyString(), any(byte[].class));

        byte[] bytes = "retry test content is valid UTF-8 text for ingestion pipeline.".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown", bytes))
                        .param("docName", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INGESTED"));

        String storagePath = jdbcTemplate.queryForObject(
                "SELECT df.storage_path FROM document_file df JOIN document d ON d.id = df.document_id "
                        + "WHERE d.tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?) AND d.doc_name = ?",
                String.class, TENANT, DOC);
        assertThat(storagePath).isEqualTo("document_file/" + TENANT + "/" + DOC + "/v1");
        assertThat(blobBytes(storagePath)).isEqualTo(bytes);
    }

    @Test
    void downloadStillWorksAfterRetryableWrite() throws Exception {
        byte[] bytes = "ordinary content for download after retry scenario.".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", FILE_NAME, "text/markdown", bytes))
                        .param("docName", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk());

        byte[] downloaded = mockMvc.perform(get("/api/v1/documents/{docName}/file", DOC)
                        .header(TenantContextInterceptor.TENANT_HEADER, TENANT))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(bytes);
    }
}

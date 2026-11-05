package com.unirag.upload;

import com.unirag.ingestion.AbstractIngestionIT;
import lombok.extern.slf4j.Slf4j;
import org.apache.opendal.OpenDALException;
import org.apache.opendal.Operator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 上传侧集成测试基类：在摄取基座之上补充 Web 层（MockMvc）、原文存储（OpenDAL Operator）
 * 与上传三表的清理（document_file 先于 document 删除，遵守 FK 顺序）。
 * 019：按租户前缀清理 RustFS 对象（document_file/{tenant}/ 与 upload_chunk/{tenant}/）。
 */
@Slf4j
@AutoConfigureMockMvc
public abstract class AbstractUploadIT extends AbstractIngestionIT {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected Operator documentStorageOperator;

    /** 清理本类租户的上传相关残留（含元数据行、会话/块索引与 RustFS 对象）。 */
    protected void cleanupUploadTenant(String tenantCode, String... vectorTables) {
        Long tenantId = jdbcTemplate.query(
                "SELECT id FROM tenant WHERE tenant_code = ?", rs -> rs.next() ? rs.getLong(1) : null, tenantCode);
        if (tenantId != null) {
            jdbcTemplate.update("DELETE FROM document_file WHERE document_id IN (SELECT id FROM document WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM upload_chunk WHERE session_id IN (SELECT id FROM upload_session WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM upload_session WHERE tenant_id = ?", tenantId);
        }
        removeAllQuietly("document_file/" + tenantCode + "/");
        removeAllQuietly("upload_chunk/" + tenantCode + "/");
        super.cleanupTenant(tenantCode, vectorTables);
    }

    private void removeAllQuietly(String prefix) {
        try {
            documentStorageOperator.removeAll(prefix);
        } catch (Exception e) {
            log.warn("清理租户对象前缀失败（跳过）: prefix={}", prefix);
        }
    }

    protected byte[] blobBytes(String storagePath) {
        return withStorageRetry(() -> documentStorageOperator.read(storagePath));
    }

    protected boolean blobExists(String storagePath) {
        try {
            withStorageRetry(() -> {
                documentStorageOperator.stat(storagePath);
                return null;
            });
            return true;
        } catch (OpenDALException e) {
            if (e.getCode() == OpenDALException.Code.NotFound) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 测试侧重试：客户端连接池复用存在竞态（research R4：服务端响应均 200、无 Connection: close，
     * 失败为客户端池化连接复用时序问题，成功/失败交替）；重试次数需足以覆盖交替失败。
     */
    protected static <T> T withStorageRetry(java.util.function.Supplier<T> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (attempt >= 6) {
                    throw e;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}

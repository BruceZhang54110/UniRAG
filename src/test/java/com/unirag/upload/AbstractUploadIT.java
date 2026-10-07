package com.unirag.upload;

import com.unirag.ingestion.AbstractIngestionIT;
import org.apache.opendal.Operator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 上传侧集成测试基类：在摄取基座之上补充 Web 层（MockMvc）、原文存储（OpenDAL Operator）
 * 与上传三表的清理（document_file 先于 document 删除，遵守 FK 顺序）。
 */
@AutoConfigureMockMvc
public abstract class AbstractUploadIT extends AbstractIngestionIT {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected Operator documentStorageOperator;

    /** 清理本类租户的上传相关残留（含 document_file/upload_session/upload_chunk，FK 顺序安全）。 */
    protected void cleanupUploadTenant(String tenantCode, String... vectorTables) {
        Long tenantId = jdbcTemplate.query(
                "SELECT id FROM tenant WHERE tenant_code = ?", rs -> rs.next() ? rs.getLong(1) : null, tenantCode);
        if (tenantId != null) {
            jdbcTemplate.update("DELETE FROM document_file WHERE document_id IN (SELECT id FROM document WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM upload_chunk WHERE session_id IN (SELECT id FROM upload_session WHERE tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM upload_session WHERE tenant_id = ?", tenantId);
        }
        super.cleanupTenant(tenantCode, vectorTables);
    }

    protected byte[] blobBytes(String storagePath) {
        return documentStorageOperator.read(storagePath);
    }

    protected boolean blobExists(String storagePath) {
        try {
            documentStorageOperator.stat(storagePath);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

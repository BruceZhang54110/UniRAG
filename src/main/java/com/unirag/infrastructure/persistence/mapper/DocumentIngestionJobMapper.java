package com.unirag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.persistence.entity.DocumentIngestionJob;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DocumentIngestionJobMapper extends BaseMapper<DocumentIngestionJob> {
    List<DocumentIngestionJob> claimBatch(@Param("limit") int limit, @Param("leaseMillis") long leaseMillis);

    int markIngesting(@Param("id") Long id, @Param("token") String token);

    DocumentIngestionJob lockForReady(@Param("id") Long id, @Param("tenantId") Long tenantId,
                                      @Param("token") String token, @Param("versionId") Long versionId);

    int markRetryOrFailed(@Param("id") Long id, @Param("token") String token,
                              @Param("attempt") int attempt, @Param("maxAttempts") int maxAttempts,
                              @Param("code") String code, @Param("message") String message,
                              @Param("delayMillis") long delayMillis);

    int recoverExpiredLeases();

    Long lockTenant(@Param("tenantId") Long tenantId);

    Integer nextVersion(@Param("tenantId") Long tenantId, @Param("documentId") Long documentId);

    int insertVersionSnapshot(@Param("version") com.unirag.infrastructure.persistence.entity.DocumentIngestionVersion version);

    int insertJob(@Param("job") DocumentIngestionJob job);

    String findDocumentName(@Param("tenantId") Long tenantId, @Param("documentId") Long documentId);

    int countVersionChunks(@Param("documentId") Long documentId, @Param("versionId") Long versionId);

    int deactivateActiveVersions(@Param("tenantId") Long tenantId, @Param("documentId") Long documentId,
                                 @Param("versionId") Long versionId);

    int activateVersion(@Param("tenantId") Long tenantId, @Param("documentId") Long documentId,
                        @Param("versionId") Long versionId);

    int finalizeReady(@Param("id") Long id, @Param("token") String token, @Param("versionId") Long versionId);

    int updateDocumentReady(@Param("tenantId") Long tenantId, @Param("documentId") Long documentId,
                            @Param("versionId") Long versionId);
}

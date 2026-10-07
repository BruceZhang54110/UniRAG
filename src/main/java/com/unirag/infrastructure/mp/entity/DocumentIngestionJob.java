package com.unirag.infrastructure.mp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("document_ingestion_job")
public class DocumentIngestionJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("document_id")
    private Long documentId;

    @TableField("document_version_id")
    private Long documentVersionId;

    @TableField("document_version")
    private Integer documentVersion;

    @TableField("content_fingerprint")
    private String contentFingerprint;

    private String status;
    private String stage;

    @TableField("attempt_count")
    private Integer attemptCount;

    @TableField("max_attempts")
    private Integer maxAttempts;

    @TableField("next_attempt_at")
    private Date nextAttemptAt;

    @TableField("lease_token")
    private String leaseToken;

    @TableField("leased_until")
    private Date leasedUntil;

    @TableField("last_error_code")
    private String lastErrorCode;

    @TableField("last_error_message")
    private String lastErrorMessage;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;

    @TableField("started_at")
    private Date startedAt;

    @TableField("finished_at")
    private Date finishedAt;
}

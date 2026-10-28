package com.unirag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 文档：一份被摄取的文本内容（doc_name 租户内唯一，replace 语义裁决者，research.md R5）。
 *
 * @TableName document
 */
@Data
@TableName("document")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("doc_name")
    private String docName;

    /** 展示名，可空。 */
    @TableField("title")
    private String title;

    /** SHA-256 hex，重复摄取识别（research.md R4）。 */
    @TableField("content_fingerprint")
    private String contentFingerprint;

    /** INGESTING / INGESTED / PARTIAL / FAILED。 */
    @TableField("status")
    private String status;

    @TableField("chunk_count")
    private Integer chunkCount;

    /** 每次内容变更重摄取 +1。 */
    @TableField("version")
    private Integer version;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;

    @TableField("deleted")
    private Boolean deleted;

    /** Async worker only: fixed version snapshot used to isolate chunks and runs. */
    @TableField(exist = false)
    private Long ingestionVersionId;

    @TableField(exist = false)
    private Long ingestionJobId;
}

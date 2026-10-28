package com.unirag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("document_ingestion_version")
public class DocumentIngestionVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("document_id")
    private Long documentId;

    private Integer version;

    @TableField("content_fingerprint")
    private String contentFingerprint;

    @TableField("storage_path")
    private String storagePath;

    private String title;

    @TableField("file_name")
    private String fileName;

    @TableField("mime_type")
    private String mimeType;

    @TableField("size_bytes")
    private Long sizeBytes;

    private Boolean active;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}

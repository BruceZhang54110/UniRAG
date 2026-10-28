package com.unirag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 文档原文元数据：与 document 1:1；blob 本体在 document_blob（经 OpenDAL，data-model.md）。
 *
 * @TableName document_file
 */
@Data
@TableName("document_file")
public class DocumentFile {

    @TableId("document_id")
    private Long documentId;

    /** OpenDAL 键：document_file/{tenantCode}/{docName}/v{version}（research R7）。 */
    @TableField("storage_path")
    private String storagePath;

    /** 归一化后的原始文件名。 */
    @TableField("file_name")
    private String fileName;

    @TableField("mime_type")
    private String mimeType;

    @TableField("size_bytes")
    private Long sizeBytes;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}

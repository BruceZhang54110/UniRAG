package com.unirag.infrastructure.mp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 分块上传会话：完成（转生为 document）或过期清理，均硬删除（data-model.md）。
 *
 * @TableName upload_session
 */
@Data
@TableName("upload_session")
public class UploadSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("doc_name")
    private String docName;

    @TableField("title")
    private String title;

    @TableField("total_size")
    private Long totalSize;

    @TableField("chunk_size")
    private Integer chunkSize;

    @TableField("total_chunks")
    private Integer totalChunks;

    /** 声明的全文 SHA-256 hex，完成时校验（research R4）。 */
    @TableField("content_sha256")
    private String contentSha256;

    /** INIT / COMPLETED / EXPIRED。 */
    @TableField("status")
    private String status;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;

    @TableField("expires_at")
    private Date expiresAt;
}

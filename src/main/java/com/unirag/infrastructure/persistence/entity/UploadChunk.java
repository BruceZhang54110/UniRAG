package com.unirag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 分块暂存索引：主键 (session_id, seq)，同 seq 重传 = 幂等覆盖（断点续传裁决者，research R4/019 R5）。
 * 块字节存 RustFS 对象（upload_chunk/{tenantCode}/{sessionId}/{seq}），本表只存对象键与字节数。
 *
 * @TableName upload_chunk
 */
@Data
@TableName("upload_chunk")
public class UploadChunk {

    @TableField("session_id")
    private Long sessionId;

    @TableField("seq")
    private Integer seq;

    @TableField("size_bytes")
    private Integer sizeBytes;

    /** RustFS 对象键（019：替代原 data bytea 列）。 */
    @TableField("storage_path")
    private String storagePath;

    @TableField("created_at")
    private Date createdAt;
}

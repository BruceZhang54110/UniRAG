package com.unirag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 分块暂存：主键 (session_id, seq)，同 seq 重传 = 幂等覆盖（断点续传裁决者，research R4）。
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

    @TableField("data")
    private byte[] data;

    @TableField("created_at")
    private Date createdAt;
}

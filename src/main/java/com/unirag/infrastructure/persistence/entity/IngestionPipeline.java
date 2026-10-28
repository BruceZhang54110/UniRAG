package com.unirag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 摄取管线：租户内一条命名的摄取配置（key 租户内唯一，research.md R2/R5）。
 *
 * @TableName ingestion_pipeline
 */
@Data
@TableName("ingestion_pipeline")
public class IngestionPipeline {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("pipeline_key")
    private String pipelineKey;

    /** 切片策略（v1 仅 RECURSIVE，research.md R3）。 */
    @TableField("strategy")
    private String strategy;

    @TableField("max_segment_tokens")
    private Integer maxSegmentTokens;

    @TableField("overlap_tokens")
    private Integer overlapTokens;

    /** 引用租户 EMBEDDING_STORE 资源 key（非外键，003 tenant_resource_config）。 */
    @TableField("embedding_store_key")
    private String embeddingStoreKey;

    /** 引用租户 EMBEDDING_MODEL 资源 key（非外键）。 */
    @TableField("embedding_model_key")
    private String embeddingModelKey;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;

    @TableField("deleted")
    private Boolean deleted;
}

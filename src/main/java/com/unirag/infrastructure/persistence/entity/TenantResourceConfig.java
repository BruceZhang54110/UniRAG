package com.unirag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.Date;

/**
 * @TableName tenant_resource_config
 */
@Data
@TableName(value = "tenant_resource_config", autoResultMap = true)
public class TenantResourceConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    /** 联表查询时从 tenant.tenant_code 带出，非本表字段。 */
    @TableField(exist = false)
    private String tenantCode;

    @TableField("resource_type")
    private String resourceType;

    @TableField("resource_key")
    private String resourceKey;

    @TableField("is_primary")
    private Boolean primary;

    @TableField(value = "config", typeHandler = JacksonTypeHandler.class)
    private JsonNode config;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;

    @TableField("deleted")
    private Boolean deleted;
}

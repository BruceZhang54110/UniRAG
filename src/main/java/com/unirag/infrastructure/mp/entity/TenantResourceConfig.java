package com.unirag.infrastructure.mp.entity;

import java.util.Date;
import lombok.Data;

/**
 * 
 * @TableName tenant_resource_config
 */
@Data
public class TenantResourceConfig {
    /**
     * 
     */
    private Long id;

    /**
     * 
     */
    private Long tenant_id;

    /**
     * 
     */
    private String resource_type;

    /**
     * 
     */
    private String resource_key;

    /**
     * 
     */
    private Boolean is_primary;

    /**
     * 
     */
    private Object config;

    /**
     * 
     */
    private Boolean enabled;

    /**
     * 
     */
    private Date created_at;

    /**
     * 
     */
    private Date updated_at;

    /**
     * 
     */
    private Boolean deleted;
}
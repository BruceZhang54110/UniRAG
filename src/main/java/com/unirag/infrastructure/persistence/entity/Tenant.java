package com.unirag.infrastructure.persistence.entity;

import java.util.Date;
import lombok.Data;

/**
 * 
 * @TableName tenant
 */
@Data
public class Tenant {
    /**
     * 
     */
    private Long id;

    /**
     * 
     */
    private String tenant_code;

    /**
     * 
     */
    private String name;

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
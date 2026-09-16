package com.unirag.tenant.resource;

import com.fasterxml.jackson.databind.JsonNode;

public class TenantResourceConfig {
    private Long id;
    private String tenantId;
    private ResourceType resourceType;
    private String resourceKey;
    private boolean primary;
    private JsonNode config;   // JSON 配置，由各 Provider 自行解析
    private boolean enabled;
}

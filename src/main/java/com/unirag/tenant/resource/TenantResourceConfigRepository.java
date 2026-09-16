package com.unirag.tenant.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TenantResourceConfigRepository {
    private final ObjectMapper objectMapper;

    public TenantResourceConfigRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
}

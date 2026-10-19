package com.unirag.tenant.resource;

/**
 * 租户资源类型。{@link #beanPrefix()} 用于 Bean 命名（{prefix}_{tenantCode}_{key}，research.md R2）。
 */
public enum ResourceType {

    LLM("llm"),

    EMBEDDING_MODEL("embeddingModel"),

    EMBEDDING_STORE("embeddingStore"),

    RERANK_MODEL("rerankModel"),

    QUERY_REWRITE_MODEL("queryRewriteModel");

    private final String beanPrefix;

    ResourceType(String beanPrefix) {
        this.beanPrefix = beanPrefix;
    }

    public String beanPrefix() {
        return beanPrefix;
    }
}

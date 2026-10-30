package com.unirag.tenant.resource;

/**
 * 租户资源类型。{@link #beanPrefix()} 用于 Bean 命名（{prefix}_{tenantCode}_{key}，research.md R2）。
 */
public enum ResourceType {

    LLM("llm"),

    EMBEDDING_MODEL("embeddingModel"),

    EMBEDDING_STORE("embeddingStore"),

    RERANK_MODEL("rerankModel"),

    QUERY_REWRITE_MODEL("queryRewriteModel"),

    /**
     * 流式聊天模型（016-sse-chat-streaming）：JSON schema 与 {@link #LLM} 完全一致，
     * 构建 {@code OpenAiStreamingChatModel}；租户未配置时流式端点降级为缓冲输出（research.md R3）。
     */
    STREAMING_LLM("streamingLlm");

    private final String beanPrefix;

    ResourceType(String beanPrefix) {
        this.beanPrefix = beanPrefix;
    }

    public String beanPrefix() {
        return beanPrefix;
    }
}

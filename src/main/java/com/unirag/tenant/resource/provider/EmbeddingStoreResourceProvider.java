package com.unirag.tenant.resource.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceConfig;
import com.unirag.tenant.resource.TenantResourceProvider;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.stereotype.Component;

import static com.unirag.tenant.resource.provider.ResourceConfigSupport.*;

/**
 * 向量存储资源配置：JSON → {@link PgVectorEmbeddingStore}。
 *
 * <p>注意：embedded PG / 无 pgvector 扩展环境下测试须配置 {@code "create-table": false}
 *（research.md R12）——构建期不执行 DDL；生产配置建议保持默认 true。
 */
@Component
public class EmbeddingStoreResourceProvider implements TenantResourceProvider<EmbeddingStore<TextSegment>> {

    @Override
    public ResourceType resourceType() {
        return ResourceType.EMBEDDING_STORE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<EmbeddingStore<TextSegment>> beanType() {
        return (Class<EmbeddingStore<TextSegment>>) (Class<?>) EmbeddingStore.class;
    }

    @Override
    public EmbeddingStore<TextSegment> build(TenantResourceConfig config) {
        JsonNode c = requireObject(config);
        var builder = PgVectorEmbeddingStore.builder()
                .host(requiredText(c, "host"))
                .port(requiredInt(c, "port"))
                .database(requiredText(c, "database"))
                .user(requiredText(c, "user"))
                .password(requiredText(c, "password"))
                .table(requiredText(c, "table"))
                .dimension(requiredInt(c, "dimension"));
        optionalBoolean(c, "create-table", builder::createTable);
        return builder.build();
    }
}

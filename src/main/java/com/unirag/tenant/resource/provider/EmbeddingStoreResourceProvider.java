package com.unirag.tenant.resource.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceConfig;
import com.unirag.tenant.resource.TenantResourceProvider;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

import static com.unirag.tenant.resource.provider.ResourceConfigSupport.*;

/**
 * 向量存储资源配置：JSON → {@link PgVectorEmbeddingStore}。
 *
 * <p>扩展创建归 Flyway V3 迁移（research.md R1），构建时一律 {@code skipCreateVectorExtension(true)}；
 * store 构建后由 {@link EmbeddingStoreTableVerifier} 完成表名白名单/存在性/维度校验（R2/R3）。
 * 冲突键 = {@code host:port:database:schema.table} 全小写（R4）。
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
        String host = requiredText(c, "host");
        int port = requiredInt(c, "port");
        String database = requiredText(c, "database");
        String user = requiredText(c, "user");
        String password = requiredText(c, "password");
        String table = requiredText(c, "table");
        int dimension = requiredInt(c, "dimension");
        boolean createTable = !c.has("create-table") || !c.get("create-table").isBoolean()
                || c.get("create-table").asBoolean();
        // 表名白名单先于 store 构建：store 将表名原样拼入 DDL（research.md R3）
        EmbeddingStoreTableVerifier.verifyTableName(table);
        var builder = PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(user)
                .password(password)
                .table(table)
                .dimension(dimension)
                .skipCreateVectorExtension(true);
        optionalBoolean(c, "create-table", builder::createTable);
        EmbeddingStore<TextSegment> store = builder.build();
        EmbeddingStoreTableVerifier.verify(host, port, database, user, password,
                table, dimension, createTable);
        return store;
    }

    @Override
    public Optional<String> collisionKey(TenantResourceConfig config) {
        JsonNode c = config.getConfig();
        if (c == null || !c.isObject()) {
            return Optional.empty();
        }
        String host = text(c, "host");
        JsonNode port = c.get("port");
        String database = text(c, "database");
        String table = text(c, "table");
        if (host == null || port == null || !port.isNumber() || database == null || table == null) {
            return Optional.empty();
        }
        String schema = "public";
        String tableName = table;
        int dot = table.indexOf('.');
        if (dot >= 0) {
            schema = table.substring(0, dot);
            tableName = table.substring(dot + 1);
        }
        return Optional.of((host + ":" + port.asInt() + ":" + database + ":" + schema + "." + tableName)
                .toLowerCase(Locale.ROOT));
    }
}

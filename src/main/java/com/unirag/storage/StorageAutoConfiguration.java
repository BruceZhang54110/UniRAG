package com.unirag.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.opendal.Operator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenDAL 存储自动配置（research R1/R2）：按 unirag.storage.backend 构建 Blocking Operator。
 *
 * <p>v1 默认 postgresql 服务（key→bytea，表 document_blob）；切 fs / s3 兼容端点只改配置，
 * 业务代码经 Operator 无感。构建失败 fail-fast（对齐 004 资源加载哲学）。
 */
@Slf4j
@Configuration
public class StorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Operator documentStorageOperator(StorageProperties props,
                                            DataSourceProperties dataSourceProperties) {
        Operator operator = switch (props.getBackend()) {
            case "postgresql" -> Operator.of("postgresql", postgresqlConfig(props, dataSourceProperties));
            case "fs" -> Operator.of("fs", Map.of("root", props.getFsRoot()));
            case "s3" -> Operator.of("s3", s3Config(props));
            default -> throw new IllegalStateException(
                    "未知存储后端: " + props.getBackend() + "（可选 postgresql|fs|s3）");
        };
        log.info("OpenDAL 存储后端就绪: backend={}, info={}", props.getBackend(), operator.info);
        return operator;
    }

    private Map<String, String> postgresqlConfig(StorageProperties props,
                                                 DataSourceProperties ds) {
        String connectionString = props.getConnectionString();
        if (connectionString == null || connectionString.isBlank()) {
            // 同源沿用 spring.datasource，避免密码双写（plan T002）
            connectionString = deriveConnectionString(ds);
        }
        Map<String, String> config = new HashMap<>();
        config.put("connection_string", connectionString);
        config.put("table", props.getTable());
        config.put("key_field", props.getKeyField());
        config.put("value_field", props.getValueField());
        config.put("root", props.getRoot());
        return config;
    }

    /** jdbc:postgresql://host:port/db → postgresql://user:pass@host:port/db（userinfo 做 URL 编码）。 */
    private String deriveConnectionString(DataSourceProperties ds) {
        String url = ds.getUrl();
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("无法从 spring.datasource.url 推导 OpenDAL 连接串: " + url);
        }
        String authorityAndPath = url.substring("jdbc:postgresql://".length());
        String userinfo = encode(ds.getUsername()) + ":" + encode(ds.getPassword()) + "@";
        return "postgresql://" + userinfo + authorityAndPath;
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> s3Config(StorageProperties props) {
        StorageProperties.S3 s3 = props.getS3();
        if (s3.getBucket() == null || s3.getBucket().isBlank()) {
            throw new IllegalStateException("unirag.storage.s3.bucket 未配置");
        }
        Map<String, String> config = new HashMap<>();
        config.put("bucket", s3.getBucket());
        config.put("region", s3.getRegion() == null ? "auto" : s3.getRegion());
        if (s3.getEndpoint() != null && !s3.getEndpoint().isBlank()) {
            config.put("endpoint", s3.getEndpoint());
        }
        if (s3.getAccessKey() != null && !s3.getAccessKey().isBlank()) {
            config.put("access_key_id", s3.getAccessKey());
            config.put("secret_access_key", s3.getSecretKey());
        }
        config.put("root", props.getRoot());
        return config;
    }
}

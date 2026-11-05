package com.unirag.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.opendal.Operator;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenDAL 存储自动配置：按 unirag.storage.s3.* 构建 RustFS（S3 兼容）Blocking Operator。
 *
 * <p>后端固定为 S3 兼容对象存储；构建或启动期桶初始化失败均 fail-fast（对齐 004 资源加载哲学），
 * 错误信息不含凭据。业务代码经 {@link DocumentStorage} 使用，不直接依赖本装配。
 */
@Slf4j
@Configuration
public class StorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Operator documentStorageOperator(StorageProperties props) {
        StorageProperties.S3 s3 = props.getS3();
        if (s3.getBucket() == null || s3.getBucket().isBlank()) {
            throw new IllegalStateException("unirag.storage.s3.bucket 未配置");
        }
        Map<String, String> config = new HashMap<>();
        config.put("bucket", s3.getBucket());
        config.put("region", s3.getRegion() == null || s3.getRegion().isBlank() ? "us-east-1" : s3.getRegion());
        if (s3.getEndpoint() != null && !s3.getEndpoint().isBlank()) {
            config.put("endpoint", s3.getEndpoint());
        }
        if (s3.getAccessKey() != null && !s3.getAccessKey().isBlank()) {
            config.put("access_key_id", s3.getAccessKey());
            config.put("secret_access_key", s3.getSecretKey());
        }
        config.put("enable_virtual_host_style", String.valueOf(!s3.isPathStyle()));
        config.put("root", s3.getRoot() == null || s3.getRoot().isBlank() ? "/" : s3.getRoot());
        Operator operator = Operator.of("s3", config);
        log.info("OpenDAL 存储后端就绪: backend=s3, bucket={}, endpoint={}", s3.getBucket(), s3.getEndpoint());
        return operator;
    }

    @Bean
    public DocumentStorage documentStorage(Operator documentStorageOperator) {
        return new DocumentStorage(documentStorageOperator);
    }

    /** 启动期幂等创建桶（FR-012）；失败 fail-fast 且不输出凭据。 */
    @Bean
    public ApplicationRunner storageBucketBootstrap(StorageProperties props) {
        return args -> new S3BucketProvisioner(props.getS3()).ensureBucket();
    }
}

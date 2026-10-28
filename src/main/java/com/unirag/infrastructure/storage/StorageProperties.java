package com.unirag.infrastructure.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 原文文件存储配置（unirag.storage.*，research R1/R2）。
 *
 * <p>backend 决定 OpenDAL 构建哪个服务的 Operator：
 * postgresql（默认，key→bytea 存 document_blob）/ fs / s3。
 * 切换后端只改配置，业务代码经 Blocking Operator 无感。
 */
@Data
@ConfigurationProperties("unirag.storage")
public class StorageProperties {

    /** postgresql | fs | s3 */
    private String backend = "postgresql";

    /** OpenDAL postgresql 服务连接串（postgresql://user:pass@host/db）。 */
    private String connectionString;

    /** OpenDAL postgresql 服务键值表名。 */
    private String table = "document_blob";

    /** 键列名（对齐 OpenDAL 默认 keyField）。 */
    private String keyField = "path";

    /** 值列名（对齐 OpenDAL 默认 valueField）。 */
    private String valueField = "data";

    /** OpenDAL 键前缀根。 */
    private String root = "/";

    /** backend=fs 时的本地根目录。 */
    private String fsRoot = "./unirag-files";

    /** backend=s3 时的连接参数（S3 兼容端点如 RustFS/MinIO 亦可）。 */
    private final S3 s3 = new S3();

    @Data
    public static class S3 {
        private String bucket;
        private String endpoint;
        private String region;
        private String accessKey;
        private String secretKey;
    }
}

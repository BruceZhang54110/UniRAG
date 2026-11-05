package com.unirag.infrastructure.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 原文文件存储配置（unirag.storage.s3.*）。
 *
 * <p>存储后端固定为 S3 兼容对象存储（本机 RustFS），由 endpoint/bucket/region/凭据定位；
 * 凭据只允许经环境变量或密钥管理注入。业务代码经 Blocking Operator 无感后端差异。
 */
@Data
@ConfigurationProperties("unirag.storage")
public class StorageProperties {

    /** S3 兼容对象存储连接参数。 */
    private final S3 s3 = new S3();

    @Data
    public static class S3 {
        /** S3 兼容端点，如 http://127.0.0.1:9000（RustFS）。 */
        private String endpoint;

        /** 对象桶名。 */
        private String bucket;

        /** SigV4 签名区域，与 RustFS 启动参数一致。 */
        private String region = "us-east-1";

        private String accessKey;

        private String secretKey;

        /** 路径风格寻址（RustFS 未配置 RUSTFS_SERVER_DOMAINS 时必须为 true）。 */
        private boolean pathStyle = true;

        /** OpenDAL 键前缀根。 */
        private String root = "/";
    }
}

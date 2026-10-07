package com.unirag.upload;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 上传链路限制配置（unirag.upload.*，spec FR-003 / FR-010）。
 */
@Data
@ConfigurationProperties("unirag.upload")
public class UploadProperties {

    /** 单文件上限（字节），默认 50MB。 */
    private long maxFileSize = 50L * 1024 * 1024;

    /** 分块上传的固定块大小（字节），默认 5MB。 */
    private int chunkSize = 5 * 1024 * 1024;

    /** ≤ 此值（字节，默认 10MB）走一次性上传接口。 */
    private long oneShotThreshold = 10L * 1024 * 1024;

    /** 未完成分块会话保留时长（默认 24h），过期连同暂存块被清理。 */
    private Duration sessionTtl = Duration.ofHours(24);

    /** 过期会话清理扫描间隔（默认 1h）。 */
    private Duration janitorInterval = Duration.ofHours(1);
}

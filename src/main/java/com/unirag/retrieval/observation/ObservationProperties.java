package com.unirag.retrieval.observation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 观测配置（{@code unirag.observability.*}）。
 *
 * <p>观测与追踪记录全部持久化到 {@code retrieval_observation} 表并以此为统一查询入口（FR-006）；
 * 写库为 best-effort，失败 MUST NOT 影响检索/问答主链路。
 */
@Data
@ConfigurationProperties("unirag.observability")
public class ObservationProperties {

    /** 是否记录观测；关闭时不注册记录型扩展，主链路行为完全不变。 */
    private boolean enabled = true;

    /** 记录留存时长，超过该时长的行由清理任务物理删除。 */
    private Duration retention = Duration.ofDays(30);

    /** 清理任务执行间隔。 */
    private Duration cleanupInterval = Duration.ofHours(1);

    /** 单条错误原因的最大留存长度（与 005 ingestion_run 的约定一致）。 */
    private int maxErrorMessageLength = 500;
}

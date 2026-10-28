package com.unirag.retrieval.observation;

import com.unirag.infrastructure.persistence.service.RetrievalObservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;

/**
 * 观测留存清理（T040，research.md R3）：按 {@code unirag.observability.retention} 物理删除过期行。
 *
 * <p>清理失败 MUST NOT 影响主链路（本任务与检索/问答无共享事务），因此这里自行捕获异常、
 * 只记类型与定位信息，绝不输出异常 message（原则 V）。
 */
@Slf4j
@Component
public class ObservationRetentionJob {

    private final RetrievalObservationService observationService;
    private final ObservationProperties properties;
    private final ObservationMetrics metrics;

    public ObservationRetentionJob(RetrievalObservationService observationService,
                                   ObservationProperties properties,
                                   ObservationMetrics metrics) {
        this.observationService = observationService;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** 首扫延迟一个清理间隔，避免应用启动期与业务争用连接池。 */
    @Scheduled(fixedDelayString = "${unirag.observability.cleanup-interval:1h}",
            initialDelayString = "${unirag.observability.cleanup-interval:1h}")
    public void purgeExpired() {
        Duration retention = properties.getRetention();
        Date cutoff = new Date(System.currentTimeMillis() - retention.toMillis());
        try {
            int deleted = observationService.deleteCreatedBefore(cutoff);
            metrics.recordRetentionCleanup(deleted);
            log.info("观测留存清理完成: retention={}, deleted={}", retention, deleted);
        } catch (Exception e) {
            log.warn("观测留存清理失败（不影响主链路）: type={}", e.getClass().getName());
        }
    }
}

package com.unirag.retrieval.observation;

import com.unirag.retrieval.pipeline.StageStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 观测指标（FR-007，research.md R10）：租户可维度化的量与延迟。
 *
 * <p>标签只用租户与状态——文档名、问题文本等高基数字段 MUST NOT 进标签（避免指标爆炸），
 * 也 MUST NOT 含密钥（原则 V）。
 */
@Component
public class ObservationMetrics {

    private static final String TENANT_TAG = "tenant";

    private static final String STATUS_TAG = "status";

    private final MeterRegistry registry;

    public ObservationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRetrieval(String tenantCode, StageStatus status, Duration duration) {
        registry.counter("unirag.retrieval.requests", TENANT_TAG, tenantCode, STATUS_TAG, status.name()).increment();
        timer("unirag.retrieval.duration", tenantCode).record(duration);
    }

    public void recordQa(String tenantCode, StageStatus status, Duration duration) {
        registry.counter("unirag.qa.requests", TENANT_TAG, tenantCode, STATUS_TAG, status.name()).increment();
        timer("unirag.qa.duration", tenantCode).record(duration);
    }

    public void recordTableFailure(String tenantCode) {
        registry.counter("unirag.retrieval.table.failures", TENANT_TAG, tenantCode).increment();
    }

    /** 重排调用量与延迟（011，FR-007）；状态只取 SUCCESS/DEGRADED。 */
    public void recordRerank(String tenantCode, StageStatus status, Duration duration) {
        registry.counter("unirag.rerank.requests", TENANT_TAG, tenantCode, STATUS_TAG, status.name()).increment();
        timer("unirag.rerank.duration", tenantCode).record(duration);
    }

    /** 留存清理量（T040）：全局计数，不带租户标签（清理任务不区分租户）。 */
    public void recordRetentionCleanup(int deleted) {
        if (deleted > 0) {
            registry.counter("unirag.observability.retention.deleted").increment(deleted);
        }
    }

    private Timer timer(String name, String tenantCode) {
        return Timer.builder(name).tag(TENANT_TAG, tenantCode).register(registry);
    }
}

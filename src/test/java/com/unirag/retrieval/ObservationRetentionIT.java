package com.unirag.retrieval;

import com.unirag.infrastructure.mp.service.RetrievalObservationService;
import com.unirag.retrieval.observation.ObservationMetrics;
import com.unirag.retrieval.observation.ObservationProperties;
import com.unirag.retrieval.observation.ObservationRetentionJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * US3 留存清理验收（T036 / research.md R3）：过期记录被物理删除、未过期保留；
 * **清理失败不影响主链路**（不抛出、只告警）。
 *
 * <p>租户码全局唯一：{@code obsRetain}。
 */
class ObservationRetentionIT extends AbstractRetrievalIT {

    private static final String TENANT = "obsRetain";
    private static final String TABLE = "embeddings_obsretain_main";

    @Autowired
    private ObservationRetentionJob retentionJob;

    @Autowired
    private ObservationProperties properties;

    @Autowired
    private ObservationMetrics metrics;

    private long tenantId;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        tenantId = insertTenant(TENANT);
    }

    @Test
    void 过期记录被删除且未过期记录保留() {
        long expiredId = insertObservation(TENANT, "expired-trace", 40);
        long withinRetentionId = insertObservation(TENANT, "fresh-trace", 10);
        long currentId = insertObservation(TENANT, "current-trace", 0);

        retentionJob.purgeExpired();

        assertThat(traceIds()).containsExactlyInAnyOrder("fresh-trace", "current-trace");
        assertThat(observationExists(expiredId)).isFalse();
        assertThat(observationExists(withinRetentionId)).isTrue();
        assertThat(observationExists(currentId)).isTrue();
        assertThat(properties.getRetention().toDays()).isEqualTo(30);
    }

    @Test
    void 清理按时间物理删除且不按租户划分() {
        String peerTenant = "obsRetainPeer";
        cleanupObservations(peerTenant);
        insertTenant(peerTenant);
        long peerExpiredId = insertObservation(peerTenant, "peer-trace", 40);

        retentionJob.purgeExpired();

        // data-model.md §4：清理是按 created_at 的物理删除，属全局维护动作，但不得误删未过期行
        assertThat(observationExists(peerExpiredId)).isFalse();
        assertThat(traceIds()).isEmpty();
    }

    @Test
    void 清理失败不抛出且不影响主链路() {
        RetrievalObservationService failingService = mock(RetrievalObservationService.class);
        when(failingService.deleteCreatedBefore(any(Date.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));

        ObservationRetentionJob job = new ObservationRetentionJob(failingService, properties, metrics);

        assertThatCode(job::purgeExpired).doesNotThrowAnyException();
    }

    private long insertObservation(String tenantCode, String traceId, int ageInDays) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO retrieval_observation (trace_id, tenant_id, kind, stage, status, created_at) "
                        + "VALUES (?, (SELECT id FROM tenant WHERE tenant_code = ?), 'QA', 'MERGE', 'SUCCESS', "
                        + "NOW() - CAST(? AS INTERVAL)) RETURNING id",
                Long.class, traceId, tenantCode, ageInDays + " days");
        assertThat(id).isNotNull();
        return id;
    }

    private boolean observationExists(long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM retrieval_observation WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private java.util.List<String> traceIds() {
        return jdbcTemplate.queryForList(
                "SELECT trace_id FROM retrieval_observation WHERE tenant_id = ? ORDER BY id",
                String.class, tenantId);
    }
}

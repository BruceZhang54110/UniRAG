package com.unirag.retrieval;

import com.unirag.infrastructure.persistence.mapper.RetrievalObservationMapper;
import com.unirag.retrieval.web.ObservationItem;
import com.unirag.retrieval.web.ObservationPage;
import com.unirag.common.web.ApiException;
import com.unirag.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 统一观测查询（T039，contracts/retrieval-api.md §4，FR-006）。
 *
 * <p>以 {@code retrieval_observation} 为基准联合投影 {@code ingestion_run}，摄取侧记录与
 * 检索/问答记录在同一入口可查；租户一律取自 {@link TenantContext}，查询范围闭合在发起租户内。
 */
@Service
public class ObservationQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final int MAX_PAGE_SIZE = 100;

    private final TenantContext tenantContext;
    private final RetrievalObservationMapper observationMapper;

    public ObservationQueryService(TenantContext tenantContext, RetrievalObservationMapper observationMapper) {
        this.tenantContext = tenantContext;
        this.observationMapper = observationMapper;
    }

    public ObservationPage query(String traceId, Long documentId, String stage, String status,
                                 String from, String to, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException("VALIDATION_FAILED",
                    "分页参数不合法：page 需 ≥ 1，size 需在 1-" + MAX_PAGE_SIZE + " 之间");
        }
        Long tenantId = tenantContext.getTenantId();
        String tenantCode = tenantContext.getTenantCode();
        Timestamp fromTime = parseTime(from, "from");
        Timestamp toTime = parseTime(to, "to");

        long total = observationMapper.countUnified(tenantId, traceId, documentId, stage, status, fromTime, toTime);
        List<Map<String, Object>> rows = observationMapper.findUnified(tenantId, traceId, documentId, stage, status,
                fromTime, toTime, size, (page - 1) * size);
        return new ObservationPage(rows.stream().map(row -> toItem(row, tenantCode)).toList(), total, page, size);
    }

    private static ObservationItem toItem(Map<String, Object> row, String tenantCode) {
        return new ObservationItem(
                (String) row.get("trace_id"),
                tenantCode,
                (String) row.get("stage"),
                (String) row.get("target_key"),
                (String) row.get("status"),
                intOf(row.get("duration_ms")),
                intOf(row.get("hit_count")),
                doubleOf(row.get("top_score")),
                (String) row.get("error_message"),
                instantOf(row.get("created_at")),
                (String) row.get("detail"));
    }

    private static Timestamp parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Timestamp.from(OffsetDateTime.parse(value.strip()).toInstant());
        } catch (DateTimeParseException e) {
            throw new ApiException("VALIDATION_FAILED", field + " 需为 ISO-8601 时间，例如 2026-10-14T21:35:02Z");
        }
    }

    private static Integer intOf(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double doubleOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static String instantOf(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant().toString() : null;
    }
}

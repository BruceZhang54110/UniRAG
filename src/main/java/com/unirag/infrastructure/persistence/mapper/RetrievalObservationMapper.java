package com.unirag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unirag.infrastructure.persistence.entity.RetrievalObservation;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @description 针对表【retrieval_observation】的数据库操作Mapper
 * @Entity com.unirag.infrastructure.persistence.entity.RetrievalObservation
 */
public interface RetrievalObservationMapper extends BaseMapper<RetrievalObservation> {

    /**
     * 统一观测查询（FR-006，research.md R3）：以 retrieval_observation 为基准联合投影
     * ingestion_run，键为列名。{@code ingestion_run} 无 tenant_id，租户经 ingestion_pipeline 关联取得。
     */
    List<Map<String, Object>> findUnified(@Param("tenantId") Long tenantId,
                                          @Param("traceId") String traceId,
                                          @Param("documentId") Long documentId,
                                          @Param("stage") String stage,
                                          @Param("status") String status,
                                          @Param("from") Date from,
                                          @Param("to") Date to,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    /**
     * 统一观测查询的总条数（与 {@link #findUnified} 同过滤条件）。
     */
    long countUnified(@Param("tenantId") Long tenantId,
                      @Param("traceId") String traceId,
                      @Param("documentId") Long documentId,
                      @Param("stage") String stage,
                      @Param("status") String status,
                      @Param("from") Date from,
                      @Param("to") Date to);
}

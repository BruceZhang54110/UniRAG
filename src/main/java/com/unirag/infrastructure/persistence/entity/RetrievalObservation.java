package com.unirag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 观测记录：检索 / 问答 / 摄取三类阶段化记录同表承载（data-model.md §1，FR-006）。
 *
 * <p>{@code error_message} 与 {@code query_digest} 落库前已截断并脱敏（限长 500，不含密钥，FR-008）。
 *
 * @TableName retrieval_observation
 */
@Data
@TableName("retrieval_observation")
public class RetrievalObservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链路标识，串联一次调用的全部阶段（SC-003）。 */
    @TableField("trace_id")
    private String traceId;

    @TableField("tenant_id")
    private Long tenantId;

    /** 调用方透传的会话标识，可为空。 */
    @TableField("session_id")
    private String sessionId;

    /** RETRIEVAL / QA / INGEST。 */
    @TableField("kind")
    private String kind;

    /** 阶段枚举名（data-model.md §2）。 */
    @TableField("stage")
    private String stage;

    /** 资源 key 或向量表名（定位用），可为空。 */
    @TableField("target_key")
    private String targetKey;

    /** SUCCESS / FAILED / DEGRADED。 */
    @TableField("status")
    private String status;

    @TableField("duration_ms")
    private Integer durationMs;

    @TableField("hit_count")
    private Integer hitCount;

    @TableField("top_score")
    private Double topScore;

    /** 通用计数（如引用条数、向量化条数）。 */
    @TableField("item_count")
    private Integer itemCount;

    @TableField("error_message")
    private String errorMessage;

    /** 重排候选明细（仅 ID/分数/keep-drop），用于灰度与排障；可空。 */
    @TableField("detail")
    private String detail;

    @TableField("query_digest")
    private String queryDigest;

    /** 可空且无外键：观测写入是 best-effort，不得因文档删除而失败。 */
    @TableField("document_id")
    private Long documentId;

    @TableField("created_at")
    private Date createdAt;
}

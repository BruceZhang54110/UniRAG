package com.unirag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 完整对话历史消息（specs/017-chat-history/data-model.md §1）：append-only 明细，
 * 一行一条消息（问或答），一轮两消息同 round_seq 同事务插入。
 *
 * <p>消息全文仅落库与经接口流出；日志/观测 MUST NOT 含 content（宪法 V）。
 *
 * @TableName chat_message
 */
@Data
@TableName("chat_message")
public class ChatMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("session_id")
    private String sessionId;

    /** 会话内轮次序号，同轮两消息同值，单调递增（应用侧 MAX+1 + 唯一键双保险）。 */
    @TableField("round_seq")
    private Long roundSeq;

    /** USER / AI。 */
    @TableField("role")
    private String role;

    /** 消息全文（历史即真源，不截断）。 */
    @TableField("content")
    private String content;

    /** SUCCESS / ABORTED / FAILED，同轮两消息同值。 */
    @TableField("status")
    private String status;

    @TableField("degrade_reason")
    private String degradeReason;

    @TableField("trace_id")
    private String traceId;

    /** 该轮答案最终引用的文档标识 CSV（保留重复计票）；仅 AI 成功轮非空（023）。 */
    @TableField("cited_document_ids")
    private String citedDocumentIds;

    @TableField("created_at")
    private Date createdAt;
}

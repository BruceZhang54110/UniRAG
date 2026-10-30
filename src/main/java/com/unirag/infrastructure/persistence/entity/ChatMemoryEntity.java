package com.unirag.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 会话记忆窗口（specs/015-chat-memory/data-model.md §1）。
 *
 * <p>一个"租户 + 会话标识"一行，{@code messages} 为 LangChain4j 序列化的记忆窗口全量；
 * 滑窗逐出的消息同步从本行消失。消息原文 MUST NOT 进日志（宪法 V）。
 * 列类型 TEXT：全量替换读写、不做 JSON 内部查询，与 V9 detail 列的取舍一致（轻量化）。
 *
 * @TableName chat_memory
 */
@Data
@TableName("chat_memory")
public class ChatMemoryEntity {

    /** 复合键：{@code tenantId:sessionId}。 */
    @TableId(type = IdType.INPUT)
    private String memoryId;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("session_id")
    private String sessionId;

    /** 记忆窗口全量消息（LangChain4j {@code ChatMessageSerializer} JSON）。 */
    @TableField("messages")
    private String messages;

    @TableField("created_at")
    private Date createdAt;

    @TableField("updated_at")
    private Date updatedAt;
}

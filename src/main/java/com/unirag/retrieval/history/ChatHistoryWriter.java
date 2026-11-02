package com.unirag.retrieval.history;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unirag.infrastructure.persistence.entity.ChatMessageEntity;
import com.unirag.infrastructure.persistence.entity.ChatMemoryEntity;
import com.unirag.infrastructure.persistence.mapper.ChatMemoryMapper;
import com.unirag.infrastructure.persistence.mapper.ChatMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 完整对话历史写入器（specs/017-chat-history/research.md R2/R3/R8）：
 * append-only 明细，一轮两消息（USER/AI）同事务插入、共用 round_seq；
 * 并发竞争（记忆开关关闭且同会话并发）经唯一键 ON CONFLICT 整轮跳过，不留半轮。
 *
 * <p>全部方法 best-effort（FR-004）：任何存储异常只记 warn（traceId/租户/异常类型，
 * 不含消息内容，宪法 V），MUST NOT 影响问答主链路。
 */
@Slf4j
@Component
public class ChatHistoryWriter {

    static final int CONTENT_MAX_LENGTH = 100_000;

    private final ChatMessageMapper messageMapper;

    private final ChatMemoryMapper memoryMapper;

    public ChatHistoryWriter(ChatMessageMapper messageMapper, ChatMemoryMapper memoryMapper) {
        this.messageMapper = messageMapper;
        this.memoryMapper = memoryMapper;
    }

    /**
     * 追加一轮问答历史。status：SUCCESS（含 NO_CONTEXT 降级）/ ABORTED / FAILED；
     * answer 为 null（失败轮）时存空串。整轮为单位，冲突时跳过并 warn。
     */
    public void recordRound(Long tenantId, String sessionId, String question, String answer,
                            String status, String degradeReason, String traceId) {
        if (tenantId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            doRecordRound(tenantId, sessionId, question, answer, status, degradeReason, traceId);
        } catch (RuntimeException e) {
            log.warn("[chat-history] 历史写入失败: tenant={}, session={}, trace={}, status={}, type={}",
                    tenantId, sessionId, traceId, status, e.getClass().getName());
        }
    }

    @Transactional
    protected void doRecordRound(Long tenantId, String sessionId, String question, String answer,
                                 String status, String degradeReason, String traceId) {
        long roundSeq = messageMapper.nextRoundSeq(tenantId, sessionId);
        messageMapper.insert(message(tenantId, sessionId, roundSeq, "USER",
                question == null ? "" : question, status, degradeReason, traceId));
        messageMapper.insert(message(tenantId, sessionId, roundSeq, "AI",
                answer == null ? "" : answer, status, degradeReason, traceId));
    }

    /**
     * 分页查询历史（FR-003）：按 id 正序；total 为会话消息总数。
     */
    public PageResult findMessages(Long tenantId, String sessionId, int page, int size) {
        Page<ChatMessageEntity> mpPage = new Page<>(page + 1, size);
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getTenantId, tenantId)
                .eq(ChatMessageEntity::getSessionId, sessionId)
                .orderByAsc(ChatMessageEntity::getId);
        Page<ChatMessageEntity> result = messageMapper.selectPage(mpPage, wrapper);
        return new PageResult(result.getTotal(), result.getRecords());
    }

    /**
     * 会话存在性双判（research.md R4）：记忆行或历史行任一存在即视为存在。
     */
    public boolean existsSession(Long tenantId, String sessionId) {
        try {
            ChatMemoryEntity memory = memoryMapper.selectById(tenantId + ":" + sessionId);
            if (memory != null) {
                return true;
            }
            return messageMapper.countBySession(tenantId, sessionId) > 0;
        } catch (RuntimeException e) {
            // 存在性判定失败按不存在处理（查询侧 404），不暴露存储异常
            log.warn("[chat-history] 会话存在性判定失败: tenant={}, session={}, type={}",
                    tenantId, sessionId, e.getClass().getName());
            return false;
        }
    }

    /** 级联删除（FR-008）：删除该会话全部历史消息（幂等）。 */
    public void deleteBySession(Long tenantId, String sessionId) {
        try {
            messageMapper.deleteBySession(tenantId, sessionId);
        } catch (RuntimeException e) {
            log.warn("[chat-history] 历史删除失败: tenant={}, session={}, type={}",
                    tenantId, sessionId, e.getClass().getName());
        }
    }

    private static ChatMessageEntity message(Long tenantId, String sessionId, long roundSeq,
                                             String role, String content, String status,
                                             String degradeReason, String traceId) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setTenantId(tenantId);
        entity.setSessionId(sessionId);
        entity.setRoundSeq(roundSeq);
        entity.setRole(role);
        entity.setContent(truncate(content));
        entity.setStatus(status);
        entity.setDegradeReason(degradeReason);
        entity.setTraceId(traceId);
        entity.setCreatedAt(new Date());
        return entity;
    }

    /** 单消息入库上限：防御异常超大内容（如误传文件内容），与观测截断同精神但宽松得多。 */
    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= CONTENT_MAX_LENGTH
                ? content
                : content.substring(0, CONTENT_MAX_LENGTH);
    }

    /** 分页结果（契约见 contracts/chat-history-endpoint.md）。 */
    public record PageResult(long total, List<ChatMessageEntity> items) {
    }
}

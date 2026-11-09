package com.unirag.retrieval.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unirag.infrastructure.persistence.entity.ChatMessageEntity;
import com.unirag.infrastructure.persistence.entity.Document;
import com.unirag.infrastructure.persistence.mapper.ChatMessageMapper;
import com.unirag.infrastructure.persistence.mapper.DocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 引用继承解析器（023，research R3）：指代型追问的确定性文档作用域判定。
 *
 * <p>判定链：开关/会话 → 指代词表 → 最新一轮 AI 回答（round_seq 最大，单条索引查询）→
 * 引用频次唯一最多者 → 目标文档未删除。任何异常收敛为 {@code RESOLVER_ERROR}（fail-open），
 * 本组件不抛出——调用方零异常处理（spec FR-008）。只认最新一轮，不向前追溯。
 */
@Slf4j
@Component
public class CitedDocumentResolver {

    public static final String CONTEXT_KEY = "citationInheritDecision";

    private final CitationInheritanceProperties properties;
    private final ChatMessageMapper chatMessageMapper;
    private final DocumentMapper documentMapper;

    public CitedDocumentResolver(CitationInheritanceProperties properties,
                                 ChatMessageMapper chatMessageMapper,
                                 DocumentMapper documentMapper) {
        this.properties = properties;
        this.chatMessageMapper = chatMessageMapper;
        this.documentMapper = documentMapper;
    }

    /**
     * 解析本轮追问是否继承上一轮引用的文档。返回决策对象（含原因），documentId 仅
     * INHERITED 时非空；其余原因一律等价于"全库检索"（与改造前行为一致）。
     */
    public InheritDecision resolve(Long tenantId, String sessionId, String question) {
        try {
            if (!properties.enabled()) {
                return InheritDecision.skipped(InheritDecision.Reason.NO_ANAPHORA, null);
            }
            if (sessionId == null || sessionId.isBlank()) {
                return InheritDecision.skipped(InheritDecision.Reason.NO_SESSION, null);
            }
            if (!properties.matchesAnaphora(question)) {
                return InheritDecision.skipped(InheritDecision.Reason.NO_ANAPHORA, null);
            }
            String matchedTerm = properties.matchedTerm(question);

            ChatMessageEntity previous = latestAiRound(tenantId, sessionId);
            if (previous == null) {
                return InheritDecision.skipped(InheritDecision.Reason.NO_PREVIOUS_ROUND, matchedTerm);
            }
            if (!"SUCCESS".equals(previous.getStatus())
                    || previous.getCitedDocumentIds() == null
                    || previous.getCitedDocumentIds().isBlank()) {
                return InheritDecision.skipped(InheritDecision.Reason.PREVIOUS_NOT_CITABLE, matchedTerm);
            }

            Map<Long, Long> votes = countVotes(previous.getCitedDocumentIds());
            Long winner = uniqueTop(votes);
            if (winner == null) {
                return InheritDecision.skipped(InheritDecision.Reason.AMBIGUOUS_CITATIONS, matchedTerm);
            }
            Document document = documentMapper.selectById(winner);
            if (document == null || Boolean.TRUE.equals(document.getDeleted())) {
                return InheritDecision.skipped(InheritDecision.Reason.DOCUMENT_DELETED, matchedTerm);
            }
            return InheritDecision.inherited(winner, matchedTerm);
        } catch (RuntimeException e) {
            // fail-open：存储不可用/数据脏都按无继承处理，只告警（宪法 V：不含消息全文）
            log.warn("引用继承判定失败（按无继承处理）: tenantId={}, type={}",
                    tenantId, e.getClass().getSimpleName());
            return InheritDecision.skipped(InheritDecision.Reason.RESOLVER_ERROR,
                    properties.matchedTerm(question));
        }
    }

    private ChatMessageEntity latestAiRound(Long tenantId, String sessionId) {
        return chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getTenantId, tenantId)
                .eq(ChatMessageEntity::getSessionId, sessionId)
                .eq(ChatMessageEntity::getRole, "AI")
                .orderByDesc(ChatMessageEntity::getRoundSeq)
                .last("LIMIT 1"));
    }

    /** 引用票数按文档聚合（保留重复计票，research R6）。 */
    static Map<Long, Long> countVotes(String citedDocumentIds) {
        Map<Long, Long> votes = new LinkedHashMap<>();
        Arrays.stream(citedDocumentIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(s -> {
                    try {
                        long docId = Long.parseLong(s);
                        votes.merge(docId, 1L, Long::sum);
                    } catch (NumberFormatException ignored) {
                        // 脏数据跳过：个别非法值不拖垮整轮判定
                    }
                });
        return votes;
    }

    /** 唯一最多者；空映射或并列均返回 null（宁缺勿错）。 */
    static Long uniqueTop(Map<Long, Long> votes) {
        Long winner = null;
        long winnerVotes = 0;
        boolean tied = false;
        for (Map.Entry<Long, Long> entry : votes.entrySet()) {
            long v = entry.getValue();
            if (v > winnerVotes) {
                winner = entry.getKey();
                winnerVotes = v;
                tied = false;
            } else if (v == winnerVotes) {
                tied = true;
            }
        }
        return (winner == null || tied) ? null : winner;
    }
}

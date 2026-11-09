package com.unirag.retrieval.history;

import com.unirag.AbstractPostgresIntegrationTest;
import com.unirag.infrastructure.persistence.entity.ChatMessageEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写入器质量门（tasks.md T004，真实 PG）：round_seq 单调、同轮双行同事务可见、
 * 分页与 total 正确、deleteBySession 幂等、失败轮空回答落库。
 */
class ChatHistoryWriterIT extends AbstractPostgresIntegrationTest {

    @Autowired
    ChatHistoryWriter writer;

    Long tenantId = 900010L;

    String session = "s-writer-it";

    @AfterEach
    void cleanUp() {
        writer.deleteBySession(tenantId, session);
    }

    @Test
    void 轮次单调递增且同轮双行成对可见() {
        writer.recordRound(tenantId, session, "问题一", "回答一", "SUCCESS", null, "t1", null);
        writer.recordRound(tenantId, session, "问题二", null, "FAILED", "LLM_UNAVAILABLE", "t2", null);

        ChatHistoryWriter.PageResult page = writer.findMessages(tenantId, session, 0, 50);
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items()).extracting(ChatMessageEntity::getRoundSeq).containsExactly(1L, 1L, 2L, 2L);
        assertThat(page.items()).extracting(ChatMessageEntity::getRole).containsExactly("USER", "AI", "USER", "AI");
        assertThat(page.items().get(3).getContent()).isEmpty();
        assertThat(page.items().get(3).getStatus()).isEqualTo("FAILED");
        assertThat(page.items().get(3).getDegradeReason()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(page.items().get(0).getContent()).isEqualTo("问题一");
    }

    @Test
    void 分页返回正确窗口() {
        for (int i = 1; i <= 3; i++) {
            writer.recordRound(tenantId, session, "问题" + i, "回答" + i, "SUCCESS", null, "t" + i, null);
        }

        ChatHistoryWriter.PageResult first = writer.findMessages(tenantId, session, 0, 2);
        ChatHistoryWriter.PageResult second = writer.findMessages(tenantId, session, 1, 2);

        assertThat(first.total()).isEqualTo(6);
        assertThat(first.items()).hasSize(2);
        assertThat(second.items()).hasSize(2);
        assertThat(first.items().get(1).getId()).isLessThan(second.items().get(0).getId());
    }

    @Test
    void 会话存在性双判与级联删除幂等() {
        assertThat(writer.existsSession(tenantId, session)).isFalse();

        writer.recordRound(tenantId, session, "问题", "回答", "SUCCESS", null, "t1", null);
        assertThat(writer.existsSession(tenantId, session)).isTrue();

        writer.deleteBySession(tenantId, session);
        writer.deleteBySession(tenantId, session);
        assertThat(writer.existsSession(tenantId, session)).isFalse();
        assertThat(writer.findMessages(tenantId, session, 0, 10).total()).isZero();
    }
}

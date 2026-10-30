package com.unirag.retrieval.memory;

import com.unirag.AbstractPostgresIntegrationTest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储层质量门（specs/015-chat-memory/tasks.md T006，宪法 III）：真实 PG 上验证
 * get/update/delete 往返序列化、空记忆返回空列表、不存在 memoryId 的 update 创建行、
 * delete 幂等。本类不插租户业务数据，租户码约定不适用。
 */
@SpringBootTest(properties = {
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
class PgChatMemoryStoreIT extends AbstractPostgresIntegrationTest {

    @Autowired
    PgChatMemoryStore store;

    @Autowired
    JdbcTemplate jdbcTemplate;

    String memoryId;

    @AfterEach
    void cleanUp() {
        if (memoryId != null) {
            store.deleteMessages(memoryId);
        }
    }

    @Test
    void updateCreatesRowAndGetRoundTripsMessages() {
        memoryId = "900001:" + UUID.randomUUID();
        List<ChatMessage> messages = List.of(
                UserMessage.from("UniRAG 支持哪些重排模型？"),
                AiMessage.from("支持 BGE 等重排模型。"));

        store.updateMessages(memoryId, messages);

        assertThat(store.getMessages(memoryId))
                .extracting(ChatMessage::type)
                .containsExactly(messages.get(0).type(), messages.get(1).type());
        assertThat(store.getMessages(memoryId).get(0)).isInstanceOf(UserMessage.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_memory WHERE memory_id = ?", Long.class, memoryId))
                .isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM chat_memory WHERE memory_id = ?", Long.class, memoryId))
                .isEqualTo(900001L);
    }

    @Test
    void getOfMissingMemoryReturnsEmptyList() {
        assertThat(store.getMessages("900001:no-such-session")).isEmpty();
    }

    @Test
    void secondUpdateReplacesWholeWindow() {
        memoryId = "900001:" + UUID.randomUUID();
        store.updateMessages(memoryId, List.of(UserMessage.from("问题一"), AiMessage.from("回答一")));

        store.updateMessages(memoryId, List.of(UserMessage.from("问题二"), AiMessage.from("回答二")));

        List<ChatMessage> current = store.getMessages(memoryId);
        assertThat(current).hasSize(2);
        assertThat(((UserMessage) current.get(0)).singleText()).isEqualTo("问题二");
        // 全量替换语义：第一轮消息被覆盖消失（memory ≠ history）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chat_memory WHERE memory_id = ?", Long.class, memoryId))
                .isOne();
    }

    @Test
    void deleteIsIdempotent() {
        memoryId = "900001:" + UUID.randomUUID();
        store.updateMessages(memoryId, List.of(UserMessage.from("问题"), AiMessage.from("回答")));

        store.deleteMessages(memoryId);
        store.deleteMessages(memoryId);

        assertThat(store.getMessages(memoryId)).isEmpty();
    }

    @Test
    void corruptJsonFallsBackToEmptyMemory() {
        memoryId = "900001:" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO chat_memory (memory_id, tenant_id, session_id, messages) "
                + "VALUES (?, 900001, 'broken', 'not-json')", memoryId);

        assertThat(store.getMessages(memoryId)).isEmpty();
    }
}

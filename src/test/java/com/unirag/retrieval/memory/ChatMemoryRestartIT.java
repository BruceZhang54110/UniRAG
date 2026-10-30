package com.unirag.retrieval.memory;

import com.unirag.AbstractPostgresIntegrationTest;
import com.unirag.infrastructure.persistence.mapper.ChatMemoryMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US2 验收（tasks.md T013，FR-003/SC-002）：丢弃全部内存对象（模拟应用重启/实例切换）后，
 * 全新构建的 store + factory 能读到完整会话记忆并续接对话。
 *
 * <p>本类不插租户业务数据，租户码约定不适用。
 */
class ChatMemoryRestartIT extends AbstractPostgresIntegrationTest {

    @Autowired
    ChatMemoryFactory factory;

    @Autowired
    PgChatMemoryStore store;

    @Autowired
    ChatMemoryMapper mapper;

    @Autowired
    ChatMemoryProperties properties;

    @Autowired
    dev.langchain4j.model.TokenCountEstimator tokenCountEstimator;

    String memoryId;

    @AfterEach
    void cleanUp() {
        if (memoryId != null) {
            store.deleteMessages(memoryId);
        }
    }

    @Test
    void 全新实例读取持久化记忆且窗口完整() {
        long tenantId = 900002;
        String session = UUID.randomUUID().toString();
        memoryId = tenantId + ":" + session;
        ChatMemory first = factory.get(tenantId, session).orElseThrow();
        first.add(UserMessage.from("问题一"));
        first.add(AiMessage.from("回答一"));
        first.add(UserMessage.from("问题二"));
        first.add(AiMessage.from("回答二"));

        // 模拟重启：丢弃 Spring 缓存的实例，用全新 store/factory 重建（不共享任何内存态）
        PgChatMemoryStore restartedStore = new PgChatMemoryStore(mapper);
        ChatMemoryFactory restartedFactory = new ChatMemoryFactory(properties, restartedStore,
                tokenCountEstimator);

        ChatMemory restored = restartedFactory.get(tenantId, session).orElseThrow();
        List<ChatMessage> messages = restored.messages();
        assertThat(messages).hasSize(4);
        assertThat(((UserMessage) messages.get(0)).singleText()).isEqualTo("问题一");
        assertThat(((AiMessage) messages.get(3)).text()).isEqualTo("回答二");

        // 续接对话：新实例上的写入持久化回原表
        restored.add(UserMessage.from("问题三"));
        assertThat(store.getMessages(memoryId)).hasSize(5);
    }
}

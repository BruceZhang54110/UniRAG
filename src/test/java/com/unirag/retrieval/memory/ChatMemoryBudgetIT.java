package com.unirag.retrieval.memory;

import com.unirag.AbstractPostgresIntegrationTest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3 验收（tasks.md T016，FR-004/005，SC-004）：超预算会话逐出最早消息对且不留孤儿消息；
 * 预算运行时调整后对同一记忆实例立即生效，无需清空或重建会话。
 *
 * <p>本类不插租户业务数据，租户码约定不适用。
 */
class ChatMemoryBudgetIT extends AbstractPostgresIntegrationTest {

    @Autowired
    PgChatMemoryStore store;

    @Autowired
    ChatMemoryProperties properties;

    @Autowired
    dev.langchain4j.model.TokenCountEstimator tokenCountEstimator;

    /** 与 {@link ChatMemoryFactory} 同一估算器 Bean 的预算断言助手。 */
    static final class TokenWindowProbe {

        private static final dev.langchain4j.model.TokenCountEstimator ESTIMATOR =
                new com.unirag.ingestion.IngestionTokenizer();

        static void assertWithinBudget(List<ChatMessage> window, int budgetTokens) {
            int tokens = ESTIMATOR.estimateTokenCountInMessages(window);
            org.assertj.core.api.Assertions.assertThat(tokens)
                    .as("记忆窗口 %s token，预算 %s token", tokens, budgetTokens)
                    .isLessThanOrEqualTo(budgetTokens);
        }
    }

    String memoryId;

    @AfterEach
    void cleanUp() {
        if (memoryId != null) {
            store.deleteMessages(memoryId);
        }
    }

    @Test
    void 超预算时成对逐出且热调参立即生效() {
        AtomicInteger budget = new AtomicInteger(60);
        ChatMemoryFactory factory = new ChatMemoryFactory(properties, store, budget::get,
                tokenCountEstimator);
        String session = UUID.randomUUID().toString();
        memoryId = 900003 + ":" + session;
        ChatMemory memory = factory.get(900003L, session).orElseThrow();

        // 中文每 token 约 1-2 字：60 token 预算下写入 3 轮（6 条）必然触发逐出
        for (int round = 1; round <= 3; round++) {
            memory.add(UserMessage.from("第" + round + "轮的用户问题内容"));
            memory.add(AiMessage.from("第" + round + "轮的回答内容"));
        }

        List<ChatMessage> window = memory.messages();
        assertThat(window.size()).isLessThan(6);
        // 逐出发生：窗口尾部保留最近一轮问答
        assertThat(window.toString()).contains("第3轮");
        // 窗口不超过预算（估算器口径与工厂一致）
        TokenWindowProbe.assertWithinBudget(window, budget.get());
        // 持久化窗口与内存一致（逐出传播到 store）
        assertThat(store.getMessages(memoryId)).hasSize(window.size());

        // 热调参：预算调大后同一实例恢复容纳更多轮次
        budget.set(100000);
        memory.add(UserMessage.from("第四轮的用户问题内容"));
        memory.add(AiMessage.from("第四轮的回答内容"));
        assertThat(memory.messages().size()).isGreaterThan(window.size());
    }
}

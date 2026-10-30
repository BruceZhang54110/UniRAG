package com.unirag.retrieval.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话记忆工厂（research.md R11）：按 memoryId（{@code tenantId:sessionId}）缓存
 * {@link TokenWindowChatMemory} 实例；预算经 {@code dynamicMaxTokens} 每次读当前配置，
 * 调参即时生效且无需重建实例（FR-005）。
 *
 * <p>无 sessionId 或开关关闭时返回空——调用方按无状态路径处理（FR-001）。
 * token 估算直接注入容器里既有的 {@link TokenCountEstimator} Bean（ingestion 的
 * {@code IngestionTokenizer}，R4）：窗口逐出只需近似计量，同一能力不重复造实现（宪法 I）。
 */
@Component
public class ChatMemoryFactory {

    private final ChatMemoryProperties properties;
    private final PgChatMemoryStore store;
    private final java.util.function.IntSupplier budgetTokens;
    private final TokenCountEstimator estimator;
    private final ConcurrentMap<String, ChatMemory> cache = new ConcurrentHashMap<>();

    @Autowired
    public ChatMemoryFactory(ChatMemoryProperties properties, PgChatMemoryStore store,
                             TokenCountEstimator tokenCountEstimator) {
        this(properties, store, () -> properties.budgetTokens(), tokenCountEstimator);
    }

    /** 预算供给外置（默认读配置）：使"运行时可调"可被测试直接验证（FR-005）。 */
    ChatMemoryFactory(ChatMemoryProperties properties, PgChatMemoryStore store,
                      java.util.function.IntSupplier budgetTokens, TokenCountEstimator estimator) {
        this.properties = properties;
        this.store = store;
        this.budgetTokens = budgetTokens;
        this.estimator = estimator;
    }

    /** 取会话记忆；不可用（未启用/缺 sessionId）返回空。 */
    public Optional<ChatMemory> get(Long tenantId, String sessionId) {
        if (!properties.enabled() || tenantId == null || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String memoryId = memoryId(tenantId, sessionId);
        return Optional.of(cache.computeIfAbsent(memoryId, id -> TokenWindowChatMemory.builder()
                .id(id)
                .dynamicMaxTokens(ignored -> budgetTokens.getAsInt(), estimator)
                .chatMemoryStore(store)
                .alwaysKeepSystemMessageFirst(true)
                .build()));
    }

    /** 清空指定会话记忆：清缓存并删持久化行（FR-009），只影响该 memoryId。 */
    public void clear(Long tenantId, String sessionId) {
        if (tenantId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        String memoryId = memoryId(tenantId, sessionId);
        cache.remove(memoryId);
        store.deleteMessages(memoryId);
    }

    public static String memoryId(Long tenantId, String sessionId) {
        return tenantId + ":" + sessionId;
    }
}

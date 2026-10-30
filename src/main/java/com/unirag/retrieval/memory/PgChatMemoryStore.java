package com.unirag.retrieval.memory;

import com.unirag.infrastructure.persistence.entity.ChatMemoryEntity;
import com.unirag.infrastructure.persistence.mapper.ChatMemoryMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 会话记忆持久化（specs/015-chat-memory/research.md R2/R10）：ChatMemoryStore → PostgreSQL。
 *
 * <p>按官方全量替换语义整段单行存储：{@code updateMessages} 事务内行锁读取（无行则插入）
 * 后覆盖 {@code messages} 列；滑窗逐出的消息随整列覆盖同步消失。消息原文 MUST NOT 进日志
 * （宪法 V），异常只带类型与定位信息。
 */
@Slf4j
@Component
public class PgChatMemoryStore implements ChatMemoryStore {

    private final ChatMemoryMapper mapper;

    public PgChatMemoryStore(ChatMemoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        ChatMemoryEntity entity = mapper.selectById(String.valueOf(memoryId));
        if (entity == null || entity.getMessages() == null || entity.getMessages().isBlank()) {
            return List.of();
        }
        try {
            return ChatMessageDeserializer.messagesFromJson(entity.getMessages());
        } catch (RuntimeException e) {
            // 解析失败按空记忆处理：fail-open 由调用方兜底，保证问答不失败（FR-007）
            log.warn("[chat-memory] 记忆消息反序列化失败，按空记忆处理: memoryId={}, type={}",
                    memoryId, e.getClass().getName());
            return List.of();
        }
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = String.valueOf(memoryId);
        ChatMemoryEntity locked = mapper.selectByIdForUpdate(id);
        ChatMemoryEntity entity = locked == null ? new ChatMemoryEntity() : locked;
        entity.setMemoryId(id);
        if (locked == null) {
            entity.setTenantId(tenantIdOf(id));
            entity.setSessionId(sessionIdOf(id));
            entity.setCreatedAt(new Date());
        }
        entity.setMessages(ChatMessageSerializer.messagesToJson(messages));
        entity.setUpdatedAt(new Date());
        if (locked == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        mapper.deleteById(String.valueOf(memoryId));
    }

    private static Long tenantIdOf(String memoryId) {
        int separator = memoryId.indexOf(':');
        return separator > 0 ? Long.valueOf(memoryId.substring(0, separator)) : null;
    }

    private static String sessionIdOf(String memoryId) {
        int separator = memoryId.indexOf(':');
        return separator >= 0 && separator < memoryId.length() - 1
                ? memoryId.substring(separator + 1)
                : memoryId;
    }
}

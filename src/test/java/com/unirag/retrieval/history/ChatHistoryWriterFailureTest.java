package com.unirag.retrieval.history;

import com.unirag.infrastructure.persistence.mapper.ChatMemoryMapper;
import com.unirag.infrastructure.persistence.mapper.ChatMessageMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-004/SC-005 证据：历史存储故障时 recordRound / deleteBySession 不抛异常
 * （问答主链路不受影响的前提）；会话存在性判定失败按"不存在"收敛。
 */
class ChatHistoryWriterFailureTest {

    private final ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
    private final ChatMemoryMapper memoryMapper = mock(ChatMemoryMapper.class);
    private final ChatHistoryWriter writer = new ChatHistoryWriter(messageMapper, memoryMapper);

    @Test
    void 存储故障时写入与删除静默降级() {
        when(messageMapper.nextRoundSeq(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> writer.recordRound(1L, "s-1", "问题", "回答",
                "SUCCESS", null, "trace", null)).doesNotThrowAnyException();

        when(messageMapper.deleteBySession(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> writer.deleteBySession(1L, "s-1")).doesNotThrowAnyException();
    }

    @Test
    void 存在性判定失败按不存在收敛() {
        when(memoryMapper.selectById(anyString())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> {
            boolean exists = writer.existsSession(1L, "s-1");
            org.assertj.core.api.Assertions.assertThat(exists).isFalse();
        }).doesNotThrowAnyException();
    }
}

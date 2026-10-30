-- 会话记忆表（015-chat-memory，specs/015-chat-memory/data-model.md §1）：
-- 每个"租户 + 会话标识"一行，整段记忆窗口以 JSONB 全量存储（LangChain4j ChatMemoryStore
-- 全量替换语义）；滑窗逐出的消息同步从本表消失（memory ≠ history，完整历史留作后续 feature）。
CREATE TABLE chat_memory (
    memory_id   VARCHAR(128) PRIMARY KEY,              -- 复合键: tenantId + ':' + sessionId
    tenant_id   BIGINT       NOT NULL,
    session_id  VARCHAR(64)  NOT NULL,
    messages    TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 租户 + 会话的业务查询入口（清空接口 WHERE 条件），同时防止同租户同会话出现两行
CREATE UNIQUE INDEX uk_chat_memory_tenant_session
    ON chat_memory (tenant_id, session_id);

COMMENT ON TABLE chat_memory IS '短期会话记忆窗口（TokenWindowChatMemory 持久化，spec 015）';

-- 完整对话历史明细（017-chat-history，specs/017-chat-history/data-model.md §1）：
-- 每轮问答（成功/降级/中止/失败）成对落库，append-only；记忆滑窗逐出不删历史。
CREATE TABLE chat_message (
    id             BIGSERIAL      PRIMARY KEY,
    tenant_id      BIGINT         NOT NULL,
    session_id     VARCHAR(64)    NOT NULL,
    round_seq      BIGINT         NOT NULL,             -- 会话内轮次序号，同轮两消息同值，单调递增
    role           VARCHAR(8)     NOT NULL,             -- USER / AI
    content        TEXT           NOT NULL,             -- 全文入库（历史即真源，不截断）
    status         VARCHAR(16)    NOT NULL,             -- SUCCESS / ABORTED / FAILED（同轮两消息同值）
    degrade_reason VARCHAR(32)         NULL,             -- NO_CONTEXT / CLIENT_ABORTED / 异常类型简名
    trace_id       VARCHAR(32)         NULL,             -- 关联观测链
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- 轮级原子可见唯一键：同轮问/答以 role 区分；并发竞争（记忆开关关闭场景）整轮
-- ON CONFLICT DO NOTHING，宁可丢整轮不留半轮（FR-005）
CREATE UNIQUE INDEX uk_chat_message_round
    ON chat_message (tenant_id, session_id, round_seq, role);

-- 分页主路径：按会话顺序扫
CREATE INDEX idx_chat_message_session
    ON chat_message (tenant_id, session_id, id);

COMMENT ON TABLE chat_message IS '完整对话历史（append-only 明细，017；与 chat_memory 滑窗互补）';

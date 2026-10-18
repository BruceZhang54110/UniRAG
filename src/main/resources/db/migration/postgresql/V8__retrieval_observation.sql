-- 观测记录表（010，data-model.md §1）：
-- 检索 / 问答 / 摄取三类记录同表承载（kind 区分），满足 FR-006"同一观测模型与查询入口"。
-- 设计要点：
--   * document_id 可空且不建外键 —— 观测写入是 best-effort，不得因文档删除或引用完整性而失败（FR-006）
--   * error_message 限长 500，与 005 ingestion_run 的既有约定一致（截断且不含密钥，FR-008）
--   * 三个索引均以 tenant_id 为前缀 —— 查询入口严格租户隔离，避免跨租户扫描
CREATE TABLE if not exists retrieval_observation (
   id            BIGSERIAL        PRIMARY KEY,
   trace_id      VARCHAR(32)      NOT NULL,
   tenant_id     BIGINT           NOT NULL REFERENCES tenant (id),
   session_id    VARCHAR(128),
   kind          VARCHAR(16)      NOT NULL,
   stage         VARCHAR(32)      NOT NULL,
   target_key    VARCHAR(128),
   status        VARCHAR(16)      NOT NULL,
   duration_ms   INT,
   hit_count     INT,
   top_score     DOUBLE PRECISION,
   item_count    INT,
   error_message VARCHAR(500),
   query_digest  VARCHAR(500),
   document_id   BIGINT,
   created_at    TIMESTAMP        NOT NULL DEFAULT NOW()
);

CREATE INDEX if not exists idx_obs_trace ON retrieval_observation (tenant_id, trace_id);

CREATE INDEX if not exists idx_obs_time ON retrieval_observation (tenant_id, created_at DESC);

CREATE INDEX if not exists idx_obs_stage ON retrieval_observation (tenant_id, stage, status);

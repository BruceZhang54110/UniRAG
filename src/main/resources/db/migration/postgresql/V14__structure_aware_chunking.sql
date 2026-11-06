-- 021 结构感知切分（structure-aware chunking，data-model.md §2/§3）：
-- document_chunk 增加章节路径（存在性判定与 DB 侧过滤支撑）；
-- ingestion_run 增加回退标注（结构感知无标题回退的观测载体）。

ALTER TABLE document_chunk
    ADD COLUMN IF NOT EXISTS section_path VARCHAR(512);

COMMENT ON COLUMN document_chunk.section_path IS '结构感知策略：最深层章节路径（如 二、重点任务/（三）…）；前言为空串；回退与既有策略为 NULL';

ALTER TABLE ingestion_run
    ADD COLUMN IF NOT EXISTS note VARCHAR(255);

COMMENT ON COLUMN ingestion_run.note IS '结构感知无标题回退标注：STRUCTURE_FALLBACK_NO_HEADING；其余场景 NULL（不复用 error_message，避免污染失败监控）';

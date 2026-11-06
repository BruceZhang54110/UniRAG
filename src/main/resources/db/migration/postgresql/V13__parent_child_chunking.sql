-- 020 父子切片（parent-child chunking，data-model.md §2/§3）：
-- document_chunk 增加层级与父子自引用关联；ingestion_pipeline 增加父块上限与回填上下文预算。

-- 切片层级：既有行与子块均为 CHILD（默认值兼容存量数据与 RECURSIVE 单级切片）
ALTER TABLE document_chunk
    ADD COLUMN IF NOT EXISTS chunk_level VARCHAR(8) NOT NULL DEFAULT 'CHILD',
    ADD COLUMN IF NOT EXISTS parent_chunk_id BIGINT;

COMMENT ON COLUMN document_chunk.chunk_level IS 'PARENT=父块(不向量化, embedding_id 为 NULL)；CHILD=子块(入向量表)';
COMMENT ON COLUMN document_chunk.parent_chunk_id IS '子块→父块自引用（同 document+pipeline+version 作用域）；父块行为 NULL';

-- 管线父子参数：max_segment_tokens/overlap_tokens 在 PARENT_CHILD 策略下承载子块参数（复用既有字段，spec 澄清 Q1）
ALTER TABLE ingestion_pipeline
    ADD COLUMN IF NOT EXISTS parent_max_tokens INT NOT NULL DEFAULT 1200,
    ADD COLUMN IF NOT EXISTS parent_context_max_chunks INT NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS parent_context_max_tokens INT NOT NULL DEFAULT 4000;

COMMENT ON COLUMN ingestion_pipeline.parent_max_tokens IS '父块 token 上限（仅 PARENT_CHILD 策略使用，必须 > max_segment_tokens）';
COMMENT ON COLUMN ingestion_pipeline.parent_context_max_chunks IS '回填上下文父块数量上限（默认 5）';
COMMENT ON COLUMN ingestion_pipeline.parent_context_max_tokens IS '回填上下文父块 token 合计上限（默认 4000）';

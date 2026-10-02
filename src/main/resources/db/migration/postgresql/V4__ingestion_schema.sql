-- 文档摄取业务表（005 文档摄取与双粒度切片，data-model.md）：
-- ingestion_pipeline（租户内命名摄取管线）/ document（文档）/ document_chunk（切片）/ ingestion_run（管线执行记录）。
-- 软删除列与列风格沿用 003 的 V2 约定（created_at/updated_at/deleted）。

-- 摄取管线：租户内按 pipeline_key 唯一（物理唯一约束允许软删除后复活先删后插，data-model.md）
CREATE TABLE if not exists ingestion_pipeline (
   id                   BIGSERIAL PRIMARY KEY,
   tenant_id            BIGINT       NOT NULL REFERENCES tenant (id),
   pipeline_key         VARCHAR(64)  NOT NULL,
   strategy             VARCHAR(32)  NOT NULL DEFAULT 'RECURSIVE',
   max_segment_tokens   INT          NOT NULL DEFAULT 500,
   overlap_tokens       INT          NOT NULL DEFAULT 50,
   embedding_store_key  VARCHAR(64)  NOT NULL,
   embedding_model_key  VARCHAR(64)  NOT NULL,
   enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
   created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
   updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
   deleted              BOOLEAN      NOT NULL DEFAULT FALSE,
   UNIQUE (tenant_id, pipeline_key)
);

-- 文档：租户内按 doc_name 唯一（replace 语义的裁决者，research.md R5）
CREATE TABLE if not exists document (
   id                   BIGSERIAL PRIMARY KEY,
   tenant_id            BIGINT       NOT NULL REFERENCES tenant (id),
   doc_name             VARCHAR(255) NOT NULL,
   title                VARCHAR(512),
   content_fingerprint  CHAR(64)     NOT NULL,
   status               VARCHAR(16)  NOT NULL,
   chunk_count          INT          NOT NULL DEFAULT 0,
   version              INT          NOT NULL DEFAULT 1,
   created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
   updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
   deleted              BOOLEAN      NOT NULL DEFAULT FALSE,
   UNIQUE (tenant_id, doc_name)
);

-- 文档切片：每 (document, pipeline) 一组；唯一性 (document_id, pipeline_id, seq_index) 由"先清后写"保证，不加物理约束
CREATE TABLE if not exists document_chunk (
   id            BIGSERIAL PRIMARY KEY,
   document_id   BIGINT  NOT NULL REFERENCES document (id),
   pipeline_id   BIGINT  NOT NULL REFERENCES ingestion_pipeline (id),
   seq_index     INT     NOT NULL,
   content       TEXT    NOT NULL,
   token_count   INT     NOT NULL,
   embedding_id  VARCHAR(64),
   created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunk_document_pipeline
    ON document_chunk (document_id, pipeline_id);

-- 管线执行记录（观测载体）：每文档×每管线一条
CREATE TABLE if not exists ingestion_run (
   id             BIGSERIAL PRIMARY KEY,
   document_id    BIGINT  NOT NULL REFERENCES document (id),
   pipeline_id    BIGINT  NOT NULL REFERENCES ingestion_pipeline (id),
   status         VARCHAR(16) NOT NULL,
   chunk_count    INT     NOT NULL DEFAULT 0,
   error_message  TEXT,
   started_at     TIMESTAMP NOT NULL DEFAULT NOW(),
   finished_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_run_document
    ON ingestion_run (document_id);

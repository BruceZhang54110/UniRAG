-- Async ingestion foundation. Existing document/document_file rows remain valid.
-- Version snapshots allow a new upload to coexist with the currently active version.
CREATE TABLE IF NOT EXISTS document_ingestion_version (
   id                   BIGSERIAL PRIMARY KEY,
   tenant_id            BIGINT       NOT NULL REFERENCES tenant (id),
   document_id          BIGINT       NOT NULL REFERENCES document (id),
   version              INT          NOT NULL,
   content_fingerprint  CHAR(64)     NOT NULL,
   storage_path         VARCHAR(1024) NOT NULL,
   title                VARCHAR(512),
   file_name            VARCHAR(512) NOT NULL,
   mime_type            VARCHAR(128) NOT NULL,
   size_bytes           BIGINT       NOT NULL,
   active               BOOLEAN      NOT NULL DEFAULT FALSE,
   created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
   updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
   UNIQUE (tenant_id, document_id, version),
   UNIQUE (tenant_id, document_id, version, content_fingerprint)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_document_ingestion_version_active
    ON document_ingestion_version (tenant_id, document_id)
    WHERE active = TRUE;

CREATE INDEX IF NOT EXISTS idx_document_ingestion_version_document
    ON document_ingestion_version (tenant_id, document_id, version);

CREATE TABLE IF NOT EXISTS document_ingestion_job (
   id                   BIGSERIAL PRIMARY KEY,
   tenant_id            BIGINT       NOT NULL REFERENCES tenant (id),
   document_id          BIGINT       NOT NULL REFERENCES document (id),
   document_version_id  BIGINT       NOT NULL REFERENCES document_ingestion_version (id),
   document_version     INT          NOT NULL,
   content_fingerprint  CHAR(64)     NOT NULL,
   status               VARCHAR(16)  NOT NULL,
   stage                VARCHAR(32)  NOT NULL,
   attempt_count        INT          NOT NULL DEFAULT 0,
   max_attempts         INT          NOT NULL DEFAULT 3,
   next_attempt_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
   lease_token          VARCHAR(128),
   leased_until         TIMESTAMP,
   last_error_code      VARCHAR(64),
   last_error_message   VARCHAR(500),
   created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
   updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
   started_at           TIMESTAMP,
   finished_at          TIMESTAMP,
   CONSTRAINT ck_document_ingestion_job_status
       CHECK (status IN ('QUEUED', 'PROCESSING', 'RETRYING', 'READY', 'FAILED')),
   CONSTRAINT ck_document_ingestion_job_stage
       CHECK (stage IN ('UPLOADED', 'CLAIMED', 'INGESTING', 'FINALIZING', 'READY', 'FAILED')),
   CONSTRAINT ck_document_ingestion_job_attempt_count CHECK (attempt_count >= 0),
   CONSTRAINT ck_document_ingestion_job_max_attempts CHECK (max_attempts > 0),
   CONSTRAINT uk_document_ingestion_job_idempotency
       UNIQUE (tenant_id, document_id, document_version, content_fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_document_ingestion_job_queue
    ON document_ingestion_job (status, next_attempt_at, leased_until);

CREATE INDEX IF NOT EXISTS idx_document_ingestion_job_tenant
    ON document_ingestion_job (tenant_id, id);

CREATE INDEX IF NOT EXISTS idx_document_ingestion_job_document_version
    ON document_ingestion_job (tenant_id, document_id, document_version);

ALTER TABLE ingestion_run
    ADD COLUMN IF NOT EXISTS job_id BIGINT REFERENCES document_ingestion_job (id),
    ADD COLUMN IF NOT EXISTS document_version_id BIGINT REFERENCES document_ingestion_version (id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ingestion_run_job_pipeline
    ON ingestion_run (job_id, pipeline_id)
    WHERE job_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ingestion_run_job
    ON ingestion_run (job_id);

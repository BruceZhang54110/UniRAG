ALTER TABLE document_chunk
    ADD COLUMN IF NOT EXISTS document_version_id BIGINT REFERENCES document_ingestion_version (id);

ALTER TABLE ingestion_run
    ADD COLUMN IF NOT EXISTS job_id BIGINT REFERENCES document_ingestion_job (id),
    ADD COLUMN IF NOT EXISTS document_version_id BIGINT REFERENCES document_ingestion_version (id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ingestion_run_job_pipeline
    ON ingestion_run (job_id, pipeline_id)
    WHERE job_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ingestion_run_version_pipeline
    ON ingestion_run (document_version_id, pipeline_id)
    WHERE document_version_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_document_chunk_version_pipeline
    ON document_chunk (document_version_id, pipeline_id);

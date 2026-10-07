-- 文档上传与文件存储（007，data-model.md）：
-- document_file（原文元数据，与 blob 分离）/ upload_session + upload_chunk（分块上传暂存）/
-- document_blob（OpenDAL postgresql 服务专用键值表，业务代码不直读写，仅经 Operator 访问）。
-- 软删除列与列风格沿用 003 的 V2 约定（created_at/updated_at/deleted）；暂存表生命周期短，不软删。

-- 原文元数据：与 document 1:1；blob 本体在 document_blob，查询路径分离（data-model.md）
CREATE TABLE if not exists document_file (
   document_id   BIGINT        PRIMARY KEY REFERENCES document (id),
   storage_path  VARCHAR(1024) NOT NULL,
   file_name     VARCHAR(512)  NOT NULL,
   mime_type     VARCHAR(128)  NOT NULL,
   size_bytes    BIGINT        NOT NULL,
   created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
   updated_at    TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- 分块上传会话：完成（转生为 document）或过期清理，均硬删除
CREATE TABLE if not exists upload_session (
   id               BIGSERIAL PRIMARY KEY,
   tenant_id        BIGINT       NOT NULL REFERENCES tenant (id),
   doc_name         VARCHAR(255) NOT NULL,
   title            VARCHAR(512),
   total_size       BIGINT       NOT NULL,
   chunk_size       INT          NOT NULL,
   total_chunks     INT          NOT NULL,
   content_sha256   CHAR(64)     NOT NULL,
   status           VARCHAR(16)  NOT NULL DEFAULT 'INIT',
   created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
   updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
   expires_at       TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_upload_session_expiry
    ON upload_session (tenant_id, status, expires_at);

-- 分块暂存：同 seq 重传 = 幂等覆盖（断点续传/乱序补传的裁决者，research R4）
CREATE TABLE if not exists upload_chunk (
   session_id   BIGINT    NOT NULL REFERENCES upload_session (id),
   seq          INT       NOT NULL,
   size_bytes   INT       NOT NULL,
   data         BYTEA     NOT NULL,
   created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
   PRIMARY KEY (session_id, seq)
);

-- OpenDAL postgresql 服务键值表：列名对齐服务默认 keyField=path / valueField=data；
-- 无外键、无额外索引（键值查找靠 PK），Flyway 拥有 DDL（data-model.md）
CREATE TABLE if not exists document_blob (
   path   TEXT PRIMARY KEY,
   data   BYTEA NOT NULL
);

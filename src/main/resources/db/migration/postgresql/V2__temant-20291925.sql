-- 创建租户基础表
CREATE TABLE  if not exists tenant (
   id          BIGSERIAL PRIMARY KEY,
   tenant_code VARCHAR(64) NOT NULL UNIQUE,  -- 对外唯一标识，如 "acme"
   name        VARCHAR(128) NOT NULL,
   enabled     BOOLEAN NOT NULL DEFAULT TRUE,
   created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
   updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
   deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_code_active
    ON tenant (tenant_code)
    WHERE deleted = FALSE;

-- 创建租户资源配置表
CREATE TABLE if not exists tenant_resource_config (
  id            BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT  NOT NULL,
  resource_type VARCHAR(32)  NOT NULL,   -- EMBEDDING_MODEL / EMBEDDING_STORE / RERANK_MODEL ...
  resource_key  VARCHAR(64)  NOT NULL,   -- 同一租户同类型下的唯一标识
  is_primary    BOOLEAN      NOT NULL DEFAULT FALSE,  -- 是否该租户该类型的默认资源
  config        JSONB        NOT NULL,   -- 具体配置，结构由各 Provider 定义
  enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (tenant_id, resource_type, resource_key)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_resource
    ON tenant_resource_config (tenant_id, resource_type, resource_key)
    WHERE deleted = FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_resource_primary
    ON tenant_resource_config (tenant_id, resource_type)
    WHERE is_primary = TRUE AND enabled = TRUE AND deleted = FALSE;
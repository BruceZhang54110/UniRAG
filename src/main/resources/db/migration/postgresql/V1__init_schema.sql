CREATE TABLE rag_test (
                          id          BIGSERIAL PRIMARY KEY,
                          name        VARCHAR(50) NOT NULL,
                          description TEXT,
                          created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

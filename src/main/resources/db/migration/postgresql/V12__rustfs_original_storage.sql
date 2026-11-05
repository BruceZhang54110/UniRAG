-- 019：原文存储切换至 RustFS（S3 兼容），数据库不再保存文件字节。
-- 初期开发不迁移存量：清空未完成分块暂存；历史脚本不可变，变更一律新增版本。
-- 约束：upload_chunk 仍保留 (session_id, seq) 主键裁决同 seq 重传幂等覆盖。

DELETE FROM upload_chunk;

ALTER TABLE upload_chunk DROP COLUMN IF EXISTS data;
ALTER TABLE upload_chunk ADD COLUMN storage_path VARCHAR(1024) NOT NULL;

DROP TABLE IF EXISTS document_blob;

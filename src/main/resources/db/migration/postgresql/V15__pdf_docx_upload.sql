-- PDF/DOCX 上传入库（022，data-model.md）：
-- 1) upload_session.file_name：分块初始化声明的原始文件名，修复 assembler 硬编码 .txt 的前置缺陷；
--    NULL 兼容旧会话，缺省回退 {docName}.txt。
-- 2) document_ingestion_job.extracted_chars：抽取步骤产出的文本字符数（FR-011 最小观测集，
--    失败归类复用既有 last_error_code，不新增列）。
ALTER TABLE upload_session ADD COLUMN IF NOT EXISTS file_name VARCHAR(512);
ALTER TABLE document_ingestion_job ADD COLUMN IF NOT EXISTS extracted_chars BIGINT;

-- 023 引用继承：上一轮答案的最终引用文档列表（AI 成功轮写入；用户轮/失败轮 NULL）。
-- 保留重复（同文档多条引用计多票），读取方按频次做并列判定；不参与任何唯一约束。
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS cited_document_ids VARCHAR(512);

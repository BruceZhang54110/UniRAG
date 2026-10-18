-- 重排序观测明细（011，data-model.md §2）：
-- detail 保存紧凑的候选顺序/分数/保留标记，仅用于灰度对比与排障；
-- 只允许 ID、pipeline key、chunk index、分数与 keep/drop，不含片段原文或密钥。
ALTER TABLE retrieval_observation ADD COLUMN IF NOT EXISTS detail VARCHAR(2000);

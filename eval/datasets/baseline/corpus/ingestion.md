# 文档摄取

摄取管线按 RECURSIVE 策略递归切分文档，优先按段落，再按句子与词。

max_segment_tokens 控制片段上限，overlap_tokens 控制相邻片段重叠。

内容指纹 content_fingerprint 用于识别重复摄取；相同内容再次摄取时不会重复写入。

切片完成后，片段写入 document_chunk，并回链到向量表中的 embedding_id。

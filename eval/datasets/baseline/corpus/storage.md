# 原文存储

原文通过 Apache OpenDAL 统一抽象存储，业务代码不感知后端差异。

默认写入 PostgreSQL bytea 键值表 document_blob。

可以通过 unirag.storage.backend 切换为 fs 或 s3。

存储层只负责原文读写，切片与向量化由摄取管线完成。

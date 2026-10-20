# 多租户配置

租户由 X-Tenant-Code 请求头定位，租户身份来自请求上下文。

向量存储配置写入 tenant_resource_config 表，resource_type 取 EMBEDDING_STORE。

系统为每个租户创建独立的向量表，避免不同租户的数据混在一起。

模型、向量存储与聊天模型都按租户配置，不在代码中硬编码地址或凭据。

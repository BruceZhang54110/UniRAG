# Quickstart: 文档摄取与双粒度切片

**Feature**: 文档摄取与双粒度切片 | **Date**: 2026-10-01

验证三个用户故事的可执行指南。设计细节见 [plan.md](./plan.md) / [data-model.md](./data-model.md) / [contracts/](./contracts/ingestion-contract.md)。

## 前置条件

- JDK 21、本地 PostgreSQL 运行中、`uni_rag_db` 存在且已装 pgvector 扩展（004 前置）
- 环境变量 `PGPASSWORD`
- 004 交付已在当前分支（V3 迁移 + 向量表机制）

## 场景 1：自动化测试（US1 + US2 + US3）

```bash
export PGPASSWORD=StrongPass123!
./mvnw test
```

预期（003/004 既有用例零回退 + 本期新增）：

- `DocumentIngestionIT`（US1，租户码 `ingUS1`）：插租户 + 一条管线（store/model 资源用测试配置指向本地）→ 摄取 5000 字中文文档 → 断言 chunk 行数 = 向量表行数、逐片回链（embedding_id + metadata 四键）、检索命中本租户、租户 B 检索为空。
- `DualGranularityIngestionIT`（US2，租户码 `dualUS2`）：coarse（上限 500）与 fine（上限 150）两条管线指向两张表 → 一次摄取 → 粗表片段数 < 细表、粗片 token > 细片、两表互不串数据；一管线指向不存在资源 → 该管线 FAILED、另一管线 SUCCESS、文档 PARTIAL。
- `IngestionReplaceAndFailureIT`（US3，租户码 `repUS3`）：同内容二次摄取 NOOP（行数不翻倍）；改内容重摄取 version+1、旧向量已删；同内容并发摄取结果确定（行数仍一致）。

## 场景 2：手工端到端复核（真实库，与自动化互为印证）

```bash
export PGPASSWORD=StrongPass123!
./mvnw spring-boot:run
```

1. 按 003 quickstart 插入测试租户（如 `handUS5`）的 EMBEDDING_STORE + EMBEDDING_MODEL 资源（模型配置可为占位，手工复核不发起真实调用则无需真凭据）
2. 插入两条管线（coarse/fine，不同 store key/表名）
3. 通过测试或调试入口触发一次摄取（本期无 HTTP 接口），观察日志：
   - `Registered tenant resource: embeddingStore_handUS5_*`
   - 摄取日志含每管线片段数与结果；失败管线 ERROR 含 tenant/pipeline/原因、无密钥
4. 库内验证：
   ```sql
   SELECT status, chunk_count, version FROM document WHERE tenant_id=(SELECT id FROM tenant WHERE tenant_code='handUS5');
   SELECT pipeline_key, count(*) FROM document_chunk GROUP BY 1;   -- 粗 < 细
   SELECT c.embedding_id IS NOT NULL, v.metadata FROM document_chunk c JOIN ...;  -- 回链与四键
   ```

## 场景 3：重复摄取语义抽查（US3 手工版）

对同一 docName 连续摄取三次：第一次 INGESTED、第二次 NOOP（chunk_count 不变）、修改内容后第三次 version=2 且 chunk 行为新内容。任一步骤后向量表行数与 document_chunk 行数恒等。

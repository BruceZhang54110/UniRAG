# Quickstart: 文档上传与文件存储

**Feature**: 文档上传与文件存储 | **Date**: 2026-10-02 | **Spec**: [spec.md](./spec.md) | **Contract**: [contracts/upload-contract.md](./contracts/upload-contract.md)

验证前提：本地 PG `uni_rag_db` 可用（`./mvnw test` 同款环境）；目标租户（如 `upUS1`）已按 003 方式注册；摄取管线按 005 方式配置（验收 US1 全链路时）。自动化测试为最终判定，本文档为手工抽查指南。

## 场景 1：自动化测试（US1 + US2 + US3 + 断点续传）

`./mvnw test`，新增 IT 类（测试数据约定沿用项目惯例：全局唯一租户码 + 复用开发库）：

- `DocumentUploadIT`（US1，租户码 `upUS1`）：一次性上传长 Markdown → 断言响应含 docName/逐管线 outcome；`document`/`document_chunk` 行数与向量表一致（005 口径）；`document_blob` 可经 OpenDAL 读回；同内容二次上传 NOOP 不翻倍；换租户检索不到。
- `ChunkedUploadResumeIT`（断点续传，租户码 `upResume`）：初始化 8 块会话 → 只传 0-4 号块 → 断点续传模拟（重新查询 receivedChunks，补传 5-7）→ complete → 下载原文逐字节比对（SC-007）；缺失块 complete → `409 UPLOAD_INCOMPLETE` 且 missingChunks 准确；同块重传幂等覆盖。
- `DocumentDownloadIT`（US2，租户码 `upDl`）：上传 → 下载字节一致、filename/Content-Type 正确；跨租户下载 404；列表分页只含本租户。
- `UploadDedupFailureIT`（US3，租户码 `upDedup`）：改内容重传同名文档 → version+1、旧 blob 键清理；超上限/非法类型/伪装二进制（`.md` 后缀的 PNG 内容）→ 入口拒绝且库中无残留；模拟 blob 表缺失 → 无半截 `document` 记录、错误可读无密钥。

## 场景 2：启动验证

`./mvnw spring-boot:run`（PG 已启动），确认：

- 启动日志无 OpenDAL 原生库加载错误（首次注意 os-maven-plugin classifier 是否匹配本机平台）；
- `GET /actuator/health` 正常（006 统一观测接入前的基线）。

## 场景 3：手工全链路抽查（curl）

```bash
TENANT=upUS1
# ① 一次性上传
curl -X POST http://localhost:8080/api/v1/documents \
  -H "X-Tenant-Code: $TENANT" \
  -F "docName=手册A" -F "title=产品手册" -F "file=@/tmp/manual.md"
# 期望：200，status=INGESTED，pipelines 含各管线 SUCCESS

# ② 查询与下载
curl -H "X-Tenant-Code: $TENANT" http://localhost:8080/api/v1/documents/手册A
curl -H "X-Tenant-Code: $TENANT" -o /tmp/manual-back.md \
  http://localhost:8080/api/v1/documents/手册A/file
diff /tmp/manual.md /tmp/manual-back.md && echo "字节一致 ✓"

# ③ 分块上传（大文件路径；seq 从 0 开始）
split -b 5m /tmp/big.md /tmp/chunk_
UP=$(curl -s -X POST http://localhost:8080/api/v1/uploads -H "X-Tenant-Code: $TENANT" \
  -H "Content-Type: application/json" \
  -d '{"docName":"大文档","totalSize":'$(stat -f%z /tmp/big.md)',"chunkSize":5242880,"totalChunks":'$(ls /tmp/chunk_* | wc -l | tr -d " ")',"contentSha256":"'$(shasum -a 256 /tmp/big.md | cut -d" " -f1)'"}' | jq -r .data.uploadId)
for i in 0 1 2 3 4 5 6 7; do          # 故意只传一半，模拟断网
  [ $i -le 3 ] && curl -s -X PUT -H "X-Tenant-Code: $TENANT" \
    --data-binary @/tmp/chunk_aa http://localhost:8080/api/v1/uploads/$UP/chunks/$i >/dev/null
done
curl -s -X POST -H "X-Tenant-Code: $TENANT" http://localhost:8080/api/v1/uploads/$UP/complete
# 期望：409 UPLOAD_INCOMPLETE，missingChunks 列出 4-7
for i in 4 5 6 7; do curl -s -X PUT -H "X-Tenant-Code: $TENANT" \
    --data-binary @/tmp/chunk_ab http://localhost:8080/api/v1/uploads/$UP/chunks/$i >/dev/null; done
# ↑ 注意：真实演示应为各块对应内容；此处仅示意流程。补传后 complete → 200
```

## 场景 4：跨 feature 闭环（依赖 006 落地后）

上传文档 → 调用 006 检索接口（`POST /api/v1/...search`，语义贴近的查询）→ 命中片段来源元数据与上传文档一致 —— 兑现 spec SC-001"上传后 30 秒内可检索"。006 未实现前，以场景 1 的 `DocumentUploadIT` 中断言替代（摄取成功 = 检索就绪，检索本身由 006 验收）。

# Contract: 健康检查端点

**Date**: 2026-09-10 | **Feature**: [spec.md](../spec.md) | 对应需求：FR-006

## GET /actuator/health

应用启动后对外暴露的唯一程序化验证接口（Spring Boot Actuator 标准端点）。

### 请求

```
GET /actuator/health HTTP/1.1
Host: localhost:8080
```

无请求参数、无请求体、无鉴权要求。

### 响应

- **成功（应用健康）**：HTTP `200 OK`，Content-Type `application/vnd.spring-boot.actuator.v3+json` 或 `application/json`

```json
{ "status": "UP" }
```

- **应用未启动或异常**：TCP 连接失败或非 2xx 响应。

### 验收断言

| 断言 | 来源 |
|------|------|
| HTTP 状态码 = 200 | SC-002、US1 验收场景 2 |
| 响应体 `status` 字段 = `UP` | data-model.md |
| 从启动命令执行到端点可响应 ≤ 30 秒 | SC-002 |

### 备注

该端点由 Actuator 自动提供，项目代码零自定义逻辑；集成测试通过 `TestRestTemplate` 或 MockMvc 断言上述契约。

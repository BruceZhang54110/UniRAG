# Data Model: 初始化 Spring Boot 3 项目骨架

**Date**: 2026-09-10 | **Feature**: [spec.md](../spec.md)

本特性为项目脚手架，无业务领域实体。以下仅记录配置域中的关键数据项及其约束。

## 构建配置（pom.xml）

| 项 | 值 | 约束来源 |
|----|----|----------|
| parent | `spring-boot-starter-parent:3.5.16` | FR-001（3 稳定版）|
| java.version | `21` | FR-002（JDK 21）|
| groupId / artifactId / version | `com.unirag` / `unirag` / `0.0.1-SNAPSHOT` | research.md R3 |
| packaging | `jar` | FR-003 |

## 应用配置（application.yml）

| 项 | 值 | 说明 |
|----|----|------|
| server.port | `8080`（默认） | 未在 spec 约束，采用 Spring Boot 默认 |
| management.endpoints.web.exposure.include | `health` | FR-006，最小暴露面 |

## 健康端点响应（运行时数据形状）

`GET /actuator/health` 返回 Spring Actuator 标准格式：

```json
{ "status": "UP" }
```

验收断言规则：HTTP 200 且响应体 `status == "UP"` 视为"应用健康可用"（对应 SC-002、US1 验收场景 2）。

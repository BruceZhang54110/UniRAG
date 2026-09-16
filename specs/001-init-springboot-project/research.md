# Phase 0 Research: 初始化 Spring Boot 3 项目骨架

**Date**: 2026-09-10 | **Feature**: [spec.md](../spec.md)

## R1: Spring Boot 版本基线

- **Decision**: Spring Boot **3.5.16**（3.x 最新稳定版，经 Maven Central `spring-boot-starter-parent` 元数据核实于 2026-09-10）。
- **Rationale**: 用户约束"使用 3 的稳定版"。Spring Initializr 当前已不提供 3.x 选项（默认 4.x），故以 Maven Central 为准锁定 3.5.16；用户在本阶段再次确认 3.5.16。
- **Alternatives considered**: Spring Boot 4.1.1（最新版，但违反"使用 3"的显式约束，放弃）；3.4.x（非 3.x 最新，放弃）。

## R2: 构建工具与 Wrapper

- **Decision**: Maven 3.9.x + Maven Wrapper（`mvnw` / `mvnw.cmd`，Wrapper 3.3.x）。
- **Rationale**: spec 假设已定为 Maven；Wrapper 让开发者免预装 Maven，满足 FR-003a"简洁完备命令"（文档仅暴露 `./mvnw` 命令）。
- **Alternatives considered**: 全局安装 Maven（违反 FR-003a，放弃）；Gradle（spec 中明确默认 Maven，放弃）。

## R3: 项目坐标与包结构

- **Decision**: groupId `com.unirag`，artifactId `unirag`，version `0.0.1-SNAPSHOT`，根包 `com.unirag`。
- **Rationale**: 与项目名 UniRAG 一致；单模块项目，尚无业务边界，不预建多余子包。

## R4: 健康检查端点（FR-006）

- **Decision**: 引入 `spring-boot-starter-actuator`，暴露 `/actuator/health`（liveness 默认 UP），作为可程序化验证启动健康的端点。
- **Rationale**: Spring Boot 官方标准方案，无需自定义代码即可获得健康语义与 JSON 响应，便于验收测试断言。
- **Alternatives considered**: 手写 `/health` Controller（重复造轮子，放弃）。

## R5: 最小依赖集

- **Decision**: `spring-boot-starter-web`（REST 骨架）+ `spring-boot-starter-actuator`（健康检查）+ `spring-boot-starter-test`（test scope）。
- **Rationale**: 满足 spec"无外部中间件"边界；依赖越少，构建越快、上手越简。

## R6: Git 基线（FR-004/FR-005）

- **Decision**: 现有仓库已初始化（`.git` 存在，分支 `001-init-springboot-project`）；本特性交付完整骨架后做一次基线提交。`.gitignore` 采用 Spring Boot 官方 Java 模板（target/、IDE 配置、日志、OS 隐藏文件）。
- **Rationale**: spec 假设明确"不重建历史，仅确保规范基线提交"。

## 遗留未知项

无。所有 NEEDS CLARIFICATION 已解决。

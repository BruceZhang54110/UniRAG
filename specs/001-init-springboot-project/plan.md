# Implementation Plan: 初始化 Spring Boot 3 项目骨架

**Branch**: `001-init-springboot-project` | **Date**: 2026-09-10 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-init-springboot-project/spec.md`

## Summary

在当前仓库（UniRAG）中搭建一个可运行的 Java Spring Boot 3.5.16 项目骨架：Maven 构建（内置 Wrapper，JDK 21 基线）、最小 web + actuator 依赖、`/actuator/health` 健康验证端点、集成测试、README 上手文档，并在 git 上完成一次覆盖完整骨架的基线提交。成功标准：开发者免预装 Maven，用不超过 3 条命令在 10 分钟内完成从克隆到健康验证的全流程。

## Technical Context

**Language/Version**: Java 21（JDK 21，spec FR-002）
**Primary Dependencies**: Spring Boot 3.5.16（`spring-boot-starter-web`、`spring-boot-starter-actuator`、`spring-boot-starter-test`）、Maven Wrapper 3.3.x
**Storage**: N/A（无外部中间件，spec 明确）
**Testing**: JUnit 5 + Spring Boot Test（`spring-boot-starter-test`）
**Target Platform**: macOS / Linux 开发机，JVM 21
**Project Type**: web-service（REST 骨架，spec 假设）
**Performance Goals**: 应用启动到可服务 ≤ 30 秒（SC-002）
**Constraints**: 完整构建（编译+测试）≤ 5 分钟且零失败（SC-003）；构建→启动 ≤ 3 条文档命令（FR-003a / SC-006）；克隆后 `git status` 干净（SC-004）
**Scale/Scope**: 单模块脚手架，无业务功能、无数据库/缓存/MQ

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` 为未批准的模板（占位符），尚未定义任何原则或门禁。

- **Phase 0 结论**: 无门禁可校验 → PASS（无违规）。
- **Phase 1 复评**: 设计产物（research.md / data-model.md / contracts / quickstart.md）均未引入违反已声明约束的内容 → PASS（无违规）。

## Project Structure

### Documentation (this feature)

```text
specs/001-init-springboot-project/
├── plan.md              # 本文件（/speckit.plan 输出）
├── research.md          # Phase 0 输出
├── data-model.md        # Phase 1 输出
├── quickstart.md        # Phase 1 输出
├── contracts/
│   └── health-endpoint.md  # Phase 1 输出
└── tasks.md             # Phase 2 输出（/speckit.tasks，本命令不生成）
```

### Source Code (repository root)

```text
UniRAG/
├── pom.xml                          # Maven 构建：parent 3.5.16，java 21，最小依赖
├── mvnw / mvnw.cmd                  # Maven Wrapper 脚本
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── .gitignore                       # Spring Boot 官方 Java 模板（target/、IDE、日志、OS 文件）
├── README.md                        # 环境要求 + 3 条命令上手（FR-007）
├── src/
│   ├── main/
│   │   ├── java/com/unirag/
│   │   │   └── UniRagApplication.java   # @SpringBootApplication 入口
│   │   └── resources/
│   │       ├── application.yml             # 端口默认 8080，仅暴露 health 端点
│   │       └── application.properties      # 不保留，统一使用 yml
│   └── test/
│       └── java/com/unirag/
│           └── UniRagApplicationTests.java  # 上下文加载测试（@SpringBootTest）
│           └── HealthEndpointIT.java           # 契约测试：断言 /actuator/health 200 + UP
└── target/                          # 构建产物，git 忽略
```

**Structure Decision**: 采用单模块标准 Maven 布局（Spring Boot 约定优于配置），根包 `com.unirag` 直接承载入口类；未来业务代码按领域在 `com.unirag` 下新增子包，本骨架不预建空目录。

## Complexity Tracking

无需填写——Constitution Check 无违规。

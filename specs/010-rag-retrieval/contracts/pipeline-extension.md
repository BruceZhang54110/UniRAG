# 契约：阶段流水线与两类扩展点

**Feature**: `specs/010-rag-retrieval/` | **Date**: 2026-10-14

本契约定义检索/问答流水线的**扩展契约**。它是 FR-012 / FR-014 的可验证落点；实现细节归 `tasks.md`。

## 1. 核心思想

一次检索/问答被拆成**数量固定的有序阶段**；想加"日志追踪"这类非核心能力，**不修改任何阶段实现**，只注册一个扩展。

```text
QUERY_EMBEDDING → VECTOR_SEARCH → MERGE → PROMPT_ASSEMBLY → CHAT_GENERATION
```

检索（US1）执行前三个阶段；问答（US2）执行全部五段。阶段之间通过 `StageContext` 传递共享状态，通过泛型 `Stage<I, O>` 声明输入输出类型。

## 2. 阶段契约

```java
public interface Stage<I, O> {
    StageName name();          // 用于观测记录与扩展点过滤
    O execute(I input, StageContext ctx);
}
```

**不变量**

- 阶段实现 MUST 是**纯编排**：外部依赖（模型、向量表、聊天模型）一律经 `TenantResourceRouter` 按租户解析，MUST NOT 自带连接或硬编码配置。
- 阶段 MUST NOT 处理横切关注点（日志、指标、追踪）—— 那是扩展点的职责。
- 阶段是**单次调用内无状态**的；跨调用的状态只能落在 `StageContext`。

## 3. 两类扩展契约（FR-012）

### 3.1 观察型 `ObservationExtension`

```java
public interface ObservationExtension {
    /** 该阶段开始前的回调；MUST NOT 修改 input。 */
    void beforeStage(StageName stage, Object input, StageContext ctx);

    /** 该阶段结束（成功或失败）后的回调；MUST NOT 修改 output。 */
    void afterStage(StageName stage, Object output, StageOutcome outcome, StageContext ctx);

    /** 关心的阶段；空集表示全部阶段。 */
    default Set<StageName> stages() { return Set.of(); }

    default FailurePolicy failurePolicy() { return FailurePolicy.FAIL_OPEN; }
}
```

**保证**

- **只读**：`input` / `output` 以只读视图传入；实现 MUST NOT 依赖任何"改写后可见"的语义。
- **数量不限、顺序无关**：可以有任意多个观察型扩展；流水线 MUST NOT 依赖它们的注册顺序。
- **执行位置确定**：所有观察型扩展的 `beforeStage` 在阶段执行前、`afterStage` 在阶段执行后（含增强型写入阶段之后）触发。因此观察型看到的是**阶段的最终输入与最终输出**，其语义不因增强型扩展的有无而改变 —— 这是 FR-012 那句"不产生歧义"的落地方式。
- 观察型扩展抛出异常时按 `failurePolicy()`（默认 `FAIL_OPEN`）处理：记录并跳过，**绝不影响主链路结果**。

### 3.2 增强型 `EnhancementExtension`

```java
public interface EnhancementExtension {
    /** 顺序，值小者先执行；MUST 显式声明。 */
    int order();

    /** 可改写阶段输入；返回新的输入。 */
    Object beforeStage(StageName stage, Object input, StageContext ctx);

    /** 可改写阶段输出，或短路（抛出 ShortCircuit）；返回新的输出。 */
    Object afterStage(StageName stage, Object output, StageContext ctx);

    default Set<StageName> stages() { return Set.of(); }

    /** 默认按性质取：可选优化 FAIL_OPEN，影响正确性者 FAIL_CLOSED。 */
    FailurePolicy failurePolicy();
}
```

**保证**

- **可写**：允许改写阶段输入/输出，也允许通过短路异常跳过后续阶段。
- **须显式排序、数量受控**：`order()` 必填；同一阶段上多个增强型扩展按 `order()` 升序执行，`order()` 重复时以 Bean 名排序兜底以保证确定性（同输入同输出）。
- 增强型失败按自身声明的 `failurePolicy()` 处理：
  - `FAIL_OPEN`：仅记录并跳过该扩展，主链路继续（用于重排、过滤等可选优化）。
  - `FAIL_CLOSED`：该阶段视为失败并进入降级（用于改写查询、改写提示词等影响结果正确性者）。

## 4. 失败语义表（FR-014）

| 扩展类别 | 默认策略 | 理由 |
|---|---|---|
| 观察型（全部） | `FAIL_OPEN` | 日志/追踪/指标失败绝不该让用户查不到答案 |
| 增强型 —— 重排、过滤 | `FAIL_OPEN` | 返回**未重排**的结果优于整条查询失败 |
| 增强型 —— 改写查询、改写提示词 | `FAIL_CLOSED` | 静默跳过会返回"语义不同却看不出差别"的结果 |

声明方式：实现 `failurePolicy()`。测试 MUST 覆盖每一类默认值与至少一个显式覆盖。

## 5. 注册方式

| 项 | 规则 |
|---|---|
| 发现机制 | Spring 容器按接口类型收集全部 Bean（`List<ObservationExtension>` / `List<EnhancementExtension>`），**MUST NOT** 需要改动 `Pipeline` 或阶段代码（FR-012 最后一句） |
| 作用域 | 全局生效（本期不按租户配置扩展），但扩展内部可经 `StageContext` 读到租户以自行决定是否生效 |
| 启用开关 | 观察型与增强型分别提供配置开关（默认开/关由 plan 定），关闭时不注册 Bean |
| 隔离 | 任一扩展抛出的异常 MUST 被 `Pipeline` 捕获并按 `failurePolicy` 处理，MUST NOT 冒泡到 Controller |

## 6. 观测与扩展的关系

`ObservationRecorder` 本身就是一个**观察型扩展**（`order` 无关），而不是阶段内的代码。这保证：

- 核心阶段代码里**没有一行**日志/指标/落库逻辑；
- 想关掉观测落库，只需不注册该 Bean；
- 想接入 OpenTelemetry（本期不做，见 spec 澄清），只需**再加一个**观察型扩展，不改任何阶段。

## 7. 可验证要点（对应 tasks 的验收）

1. 新增一个观察型扩展 Bean 后，无需修改阶段代码即被调用，且不改变检索结果（字节级比较 `chunks`）。
2. 观察型扩展抛异常时，检索结果与"未注册该扩展"完全一致。
3. 增强型扩展 `order()` 反转注册顺序不影响执行顺序。
4. `FAIL_CLOSED` 的增强型扩展抛异常时，该阶段记为失败并按降级语义返回。
5. `FAIL_OPEN` 的重排类扩展抛异常时，仍返回未重排的结果（不报错）。

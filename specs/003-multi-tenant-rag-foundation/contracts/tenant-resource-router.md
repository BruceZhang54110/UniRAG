# Contract: TenantResourceRouter

**Feature**: 多租户 RAG 资源基座 | **Date**: 2026-09-16 | **Spec**: [spec.md](../spec.md)（FR-004 / FR-011，Clarifications Q2/Q3）

对业务代码暴露的唯一取资源入口。实现类：`com.unirag.tenant.resource.TenantResourceRouter`（Spring `@Component`）。

## API

```java
public class TenantResourceRouter {

    /** 按"租户 + 类型 + 标识"获取资源实例。 */
    public <T> T get(String tenantCode, ResourceType type, String resourceKey, Class<T> beanType);

    /** 获取该租户该类型的主资源。 */
    public <T> T getPrimary(String tenantCode, ResourceType type, Class<T> beanType);
}
```

## 语义契约

### get(tenantCode, type, key, beanType)

| 情形 | 行为 |
|---|---|
| 命中且类型匹配 | 返回该租户的实例（同一调用返回同一单例） |
| 不存在（租户未知 / 类型未配置 / key 不存在） | 抛 `TenantResourceNotFoundException`，message 含 tenantCode/type/key |
| 命中但 beanType 与实际类型不符 | 抛 `BeansException`（Spring `getBean(name, type)` 原生类型检查） |

### getPrimary(tenantCode, type, beanType)

| 情形 | 行为 |
|---|---|
| 存在 primary | 返回 primary 实例 |
| 无 primary 且该 (tenant,type) 仅注册了一条 | 回退返回该唯一实例 |
| 无 primary 且注册了多条 | 抛 `TenantResourceNotFoundException`（message 提示未指定 primary 且存在多个实例） |
| 重复 primary（DB 唯一索引被绕过的防御路径） | 启动期已记错误日志，取配置 id 最小者；此处直接返回该实例 |

## 异常

```java
public class TenantResourceNotFoundException extends RuntimeException {
    // message 模板: "Tenant resource not found: tenant=<code>, type=<TYPE>, key=<key>"
    // 或 "No primary tenant resource: tenant=<code>, type=<TYPE> (N instances registered)"
}
```

调用方必须显式处理或继续上抛（Q2 决策：不用 Optional/null）。

## 使用示例

```java
ChatModel acmeLlm = router.get("acme", ResourceType.LLM, "default", ChatModel.class);
EmbeddingModel primary = router.getPrimary("acme", ResourceType.EMBEDDING_MODEL, EmbeddingModel.class);
```

## 不变量

- 返回实例必定属于指定租户（SC-002：跨租户串用为 0）。
- 所有不存在场景一律抛 `TenantResourceNotFoundException`，无 null 返回。

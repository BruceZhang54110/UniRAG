# 指代追问场景集（023 US3）

多轮会话评测场景集：验证"引用继承"的触发正确性（候选集中度 100%）与误触发率（0）。

**状态**：场景已冻结，待"会话感知 harness 扩展"落地后可执行；继承正确性的当前可执行证据见
`CitationInheritanceIT`（7 场景，见归档基线报告）。

## 为什么现有 harness 跑不了

`src/test/java/com/unirag/eval/` 的评测 harness 是**无会话单查询**检索评测（ingest corpus →
逐条检索 → span 命中率）。引用继承的输入是"上一轮答案的最终引用"，必须有会话状态才能触发；
在单查询模式下 resolver 恒返回 `NO_SESSION`，测不到任何东西。因此本数据集采用
**两阶段交付**：

1. 本目录冻结场景目录（scenarios.jsonl），作为将来 harness 扩展（会话感知执行器）的输入契约；
2. `queries.jsonl` 仅含首轮检索可检问题，保证数据集可被现有 `DatasetLoader/Validator` 加载校验
   （格式与 eval/datasets/README.md 一致），用于语料与标注的质量门。

## 指标口径（对齐 spec SC-001/SC-002）

- **候选文档集中度**：触发继承的轮次，检索候选中来自被继承文档的比例 = 100%；
- **误触发率**：负样例轮次（新话题/并列/上轮失败）出现继承标记的比例 = 0。

## scenarios.jsonl 字段

```json
{"id":"an-001","session":"sess-an-001","rounds":[
   {"role":"user","question":"……首轮，检索可检……","expect":{"decision":"INHERITED-able"}},
   {"role":"user","question":"这篇文档的核心约束是什么？",
    "expect":{"decision":"INHERITED","doc":"<首轮引用最多的文档>"}}],
 "tags":["anaphora-inherit"]}
```

- 正样例（`anaphora-inherit`）：期望 `INHERITED` 且候选全部来自目标文档；
- 负样例（`anaphora-negative`）：期望原因码为 `NO_ANAPHORA` / `AMBIGUOUS_CITATIONS` /
  `PREVIOUS_NOT_CITABLE` / `NO_PREVIOUS_ROUND`，且检索不受限。

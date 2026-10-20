# UniRAG 评测数据

本目录存放 RAG 检索评测数据集、冻结向量、基线与运行报告。维护方式见 [datasets/README.md](datasets/README.md)。

```text
eval/
├── datasets/<name>/        # 语料、查询、配置、阈值
├── fixtures/<name>/        # replay 模式使用的冻结向量
├── baselines/              # 已确认基线（版本控制）
└── reports/                # 运行报告（默认不入库）
```

默认质量门 `./mvnw test` 不执行评测；评测通过 `-Peval` 显式触发。

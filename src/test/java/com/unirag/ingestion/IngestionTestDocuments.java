package com.unirag.ingestion;

/**
 * 摄取测试文档构造：确定性中文长文档（多段落，>5000 字）与短文档。
 */
public final class IngestionTestDocuments {

    private static final String[] TOPICS = {
            "向量数据库", "检索增强生成", "嵌入模型", "语义切分",
            "租户隔离", "混合检索", "重排序", "知识库治理",
            "近似最近邻", "维度选择", "增量索引", "查询改写"};

    private IngestionTestDocuments() {
    }

    /** 约 5000 字中文长文档：12 个自然段（空行分隔），供递归切片产出多片段。 */
    public static String longChineseDocument() {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < TOPICS.length; p++) {
            sb.append("第").append(p + 1).append("章 ").append(TOPICS[p]).append("。\n\n");
            for (int s = 0; s < 4; s++) {
                sb.append(TOPICS[p]).append("是本章第").append(s + 1).append("小节的核心议题，")
                        .append("我们围绕其设计目标、约束条件与工程实践展开讨论，")
                        .append("并结合多租户场景说明配置与数据隔离的关系。")
                        .append("实践表明，合理的参数选择与可观测的执行记录能够显著降低排障成本，")
                        .append("同时保证重复摄取时的确定性与一致性。");
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /** 变更后的长文档（替换语义用：末尾追加修订段落，指纹必变）。 */
    public static String longChineseDocumentRevised() {
        return longChineseDocument() + "附则：本章为修订版内容，补充了故障隔离与替换语义的边界说明。\n\n";
    }

    /** 短文档（远小于切片上限，产出恰好一个片段）。 */
    public static String shortChineseDocument() {
        return "短文档：向量数据库入门指南，介绍嵌入与检索的基本概念。";
    }
}

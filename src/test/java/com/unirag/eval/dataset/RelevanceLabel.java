package com.unirag.eval.dataset;

/** 相关性标注：文档 + 原文片段，不使用片段序号（013 data-model §1.4）。 */
public record RelevanceLabel(String doc, String span) {
}

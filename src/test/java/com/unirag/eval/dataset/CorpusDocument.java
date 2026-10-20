package com.unirag.eval.dataset;

/** 评测语料文档（013 data-model §1.2）。 */
public record CorpusDocument(String docName, String title, String content, String sourcePath) {
}

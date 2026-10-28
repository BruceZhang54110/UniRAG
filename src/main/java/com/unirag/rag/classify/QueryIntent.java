package com.unirag.rag.classify;

/** 查询意图（data-model.md §1.1）。 */
public enum QueryIntent {
    SIMPLE_FACT,
    MULTI_HOP_RELATION,
    COMPLEX_REASONING
}

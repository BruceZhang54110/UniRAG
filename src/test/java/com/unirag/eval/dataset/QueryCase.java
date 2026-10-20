package com.unirag.eval.dataset;

import java.util.List;

/** 查询用例（013 data-model §1.3）。 */
public record QueryCase(String id,
                        String question,
                        List<String> tags,
                        String split,
                        boolean answerable,
                        List<RelevanceLabel> relevant) {

    public QueryCase {
        tags = tags == null ? List.of() : List.copyOf(tags);
        relevant = relevant == null ? List.of() : List.copyOf(relevant);
        split = split == null || split.isBlank() ? "dev" : split;
    }
}

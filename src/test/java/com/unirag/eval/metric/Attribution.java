package com.unirag.eval.metric;

import java.util.List;

/** 单条查询的失败归因与证据。 */
public record Attribution(FailureCategory category, List<String> evidence) {

    public Attribution {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public boolean isFailure() {
        return category != null;
    }

    public static Attribution none() {
        return new Attribution(null, List.of());
    }
}

package com.unirag.rag.route;

import java.util.List;

/** 纯数据路由决定（data-model.md §4）。 */
public record RouteDecision(RagParadigm targetParadigm,
                            RagParadigm actualParadigm,
                            List<RagParadigm> candidateParadigms,
                            boolean degraded,
                            List<String> reasonCodes,
                            List<CapabilityProbe> capabilityProbes,
                            long classificationDurationMs,
                            String ruleVersion) {

    public RouteDecision {
        if (targetParadigm == null || actualParadigm == null) {
            throw new IllegalArgumentException("route target/actual paradigm must not be null");
        }
        candidateParadigms = candidateParadigms == null ? List.of() : List.copyOf(candidateParadigms);
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        capabilityProbes = capabilityProbes == null ? List.of() : List.copyOf(capabilityProbes);
        if (candidateParadigms.isEmpty() || candidateParadigms.size() > 3) {
            throw new IllegalArgumentException("route candidateParadigms size must be 1..3");
        }
        if (!candidateParadigms.contains(actualParadigm)) {
            throw new IllegalArgumentException("actual paradigm must be present in candidates");
        }
        if (classificationDurationMs < 0) {
            throw new IllegalArgumentException("classificationDurationMs must be >= 0");
        }
    }
}

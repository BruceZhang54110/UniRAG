package com.unirag.rag.route;

import com.unirag.rag.classify.ClassificationOutcome;

import java.util.List;

/** ROUTE 观测受控 JSON 详情（contracts/routing-observation.md §3）。 */
public record RouteObservationDetail(int schemaVersion,
                                     String ruleVersion,
                                     ClassificationPayload classification,
                                     RoutePayload route) {

    public static RouteObservationDetail from(ClassificationOutcome outcome, RouteDecision decision) {
        return new RouteObservationDetail(1, outcome.ruleVersion(),
                ClassificationPayload.from(outcome), RoutePayload.from(decision));
    }

    public record ClassificationPayload(String status,
                                        String intent,
                                        Double confidence,
                                        String reasonCode,
                                        long durationMs,
                                        List<EvidencePayload> evidence) {

        private static ClassificationPayload from(ClassificationOutcome outcome) {
            return new ClassificationPayload(outcome.status().name(),
                    outcome.intent() == null ? null : outcome.intent().name(),
                    outcome.confidence(), outcome.reasonCode(), outcome.durationMs(),
                    outcome.evidence().stream().map(EvidencePayload::from).toList());
        }
    }

    public record EvidencePayload(String code, String intent, double weight) {

        private static EvidencePayload from(com.unirag.rag.classify.ClassificationEvidence evidence) {
            return new EvidencePayload(evidence.code(), evidence.intent().name(), evidence.weight());
        }
    }

    public record RoutePayload(String target,
                               String actual,
                               boolean degraded,
                               List<String> reasonCodes,
                               List<String> candidateParadigms,
                               List<ProbePayload> capabilityProbes) {

        private static RoutePayload from(RouteDecision decision) {
            return new RoutePayload(decision.targetParadigm().name(), decision.actualParadigm().name(),
                    decision.degraded(), decision.reasonCodes(),
                    decision.candidateParadigms().stream().map(Enum::name).toList(),
                    decision.capabilityProbes().stream().map(ProbePayload::from).toList());
        }
    }

    public record ProbePayload(String paradigm, String state, String reasonCode) {

        private static ProbePayload from(CapabilityProbe probe) {
            return new ProbePayload(probe.paradigm().name(), probe.state().name(), probe.reasonCode());
        }
    }
}

package com.unirag.rag.route;

import com.unirag.rag.classify.ClassificationOutcome;
import com.unirag.rag.classify.ClassificationStatus;
import com.unirag.rag.classify.QueryIntent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 按分类状态与能力可用性选择首个已就绪范式（contracts/rag-orchestration.md §4）。 */
@Component
public class RagRouter {

    private final RagCapabilityRegistry registry;

    public RagRouter(RagCapabilityRegistry registry) {
        this.registry = registry;
    }

    public RagRoute route(String tenantCode, ClassificationOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("classification outcome must not be null");
        }
        RoutingPlan plan = plan(outcome);
        List<String> reasonCodes = new ArrayList<>();
        if (outcome.status() != ClassificationStatus.SUCCESS) {
            reasonCodes.add(outcome.reasonCode());
        }
        List<CapabilityProbe> probes = new ArrayList<>();
        RagParadigm actual = null;
        RagParadigmExecutor selected = null;

        for (RagParadigm candidate : plan.candidates()) {
            CapabilityProbe probe = registry.probe(tenantCode, candidate);
            probes.add(probe);
            if (probe.state() == CapabilityAvailability.READY) {
                actual = candidate;
                selected = registry.executor(candidate).orElseThrow();
                break;
            }
            reasonCodes.add(probe.reasonCode());
        }
        if (actual == null || selected == null) {
            throw new IllegalStateException("No ready RAG paradigm for candidates " + plan.candidates());
        }

        boolean degraded = !plan.target().equals(actual)
                || outcome.status() != ClassificationStatus.SUCCESS
                || !reasonCodes.isEmpty();
        RouteDecision decision = new RouteDecision(plan.target(), actual, plan.candidates(), degraded,
                reasonCodes, probes, outcome.durationMs(), outcome.ruleVersion());
        return new RagRoute(decision, selected);
    }

    private static RoutingPlan plan(ClassificationOutcome outcome) {
        if (outcome.status() != ClassificationStatus.SUCCESS) {
            return new RoutingPlan(RagParadigm.AGENTIC_RAG,
                    List.of(RagParadigm.AGENTIC_RAG, RagParadigm.TRADITIONAL_RAG));
        }
        return switch (outcome.intent()) {
            case SIMPLE_FACT -> new RoutingPlan(RagParadigm.TRADITIONAL_RAG,
                    List.of(RagParadigm.TRADITIONAL_RAG));
            case MULTI_HOP_RELATION -> new RoutingPlan(RagParadigm.GRAPH_RAG,
                    List.of(RagParadigm.GRAPH_RAG, RagParadigm.AGENTIC_RAG, RagParadigm.TRADITIONAL_RAG));
            case COMPLEX_REASONING -> new RoutingPlan(RagParadigm.AGENTIC_RAG,
                    List.of(RagParadigm.AGENTIC_RAG, RagParadigm.TRADITIONAL_RAG));
        };
    }

    private record RoutingPlan(RagParadigm target, List<RagParadigm> candidates) {
    }
}

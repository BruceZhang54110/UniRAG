package com.unirag.rag.route;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 真实范式执行器注册表（contracts/rag-orchestration.md §3）。 */
@Component
public class RagCapabilityRegistry {

    private final Map<RagParadigm, RagParadigmExecutor> executors;

    public RagCapabilityRegistry(List<RagParadigmExecutor> executorBeans) {
        Map<RagParadigm, RagParadigmExecutor> registered = new EnumMap<>(RagParadigm.class);
        for (RagParadigmExecutor executor : executorBeans) {
            RagParadigmExecutor previous = registered.putIfAbsent(executor.paradigm(), executor);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate RAG paradigm executor: " + executor.paradigm());
            }
        }
        this.executors = Map.copyOf(registered);
    }

    public Optional<RagParadigmExecutor> executor(RagParadigm paradigm) {
        return Optional.ofNullable(executors.get(paradigm));
    }

    public CapabilityProbe probe(String tenantCode, RagParadigm paradigm) {
        RagParadigmExecutor executor = executors.get(paradigm);
        if (executor == null) {
            return new CapabilityProbe(paradigm, CapabilityAvailability.NOT_REGISTERED,
                    "CAPABILITY_NOT_REGISTERED");
        }
        try {
            if (!executor.enabledFor(tenantCode)) {
                return new CapabilityProbe(paradigm, CapabilityAvailability.DISABLED, "CAPABILITY_DISABLED");
            }
        } catch (RuntimeException e) {
            return new CapabilityProbe(paradigm, CapabilityAvailability.UNHEALTHY, "CAPABILITY_UNHEALTHY");
        }
        try {
            if (!executor.healthyFor(tenantCode)) {
                return new CapabilityProbe(paradigm, CapabilityAvailability.UNHEALTHY, "CAPABILITY_UNHEALTHY");
            }
        } catch (RuntimeException e) {
            return new CapabilityProbe(paradigm, CapabilityAvailability.UNHEALTHY, "CAPABILITY_UNHEALTHY");
        }
        return new CapabilityProbe(paradigm, CapabilityAvailability.READY, null);
    }
}

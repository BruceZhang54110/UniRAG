package com.unirag.rag.route;

/** 路由决定与最终执行器的运行时绑定（data-model.md §4）。 */
public record RagRoute(RouteDecision decision, RagParadigmExecutor executor) {

    public RagRoute {
        if (decision == null || executor == null) {
            throw new IllegalArgumentException("route decision/executor must not be null");
        }
        if (executor.paradigm() != decision.actualParadigm()) {
            throw new IllegalArgumentException("executor paradigm must equal actual paradigm");
        }
    }
}

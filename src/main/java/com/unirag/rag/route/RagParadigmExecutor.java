package com.unirag.rag.route;

import com.unirag.rag.model.RagRequest;
import com.unirag.rag.model.RagResult;
import com.unirag.retrieval.pipeline.StageContext;

/** 范式执行器契约（contracts/rag-orchestration.md §3）。 */
public interface RagParadigmExecutor {

    RagParadigm paradigm();

    boolean enabledFor(String tenantCode);

    boolean healthyFor(String tenantCode);

    RagResult execute(RagRequest request, StageContext ctx);
}

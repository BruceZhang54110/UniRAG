package com.unirag.rag.route;

import com.unirag.rag.classify.ClassificationEvidence;
import com.unirag.rag.classify.ClassificationOutcome;
import com.unirag.rag.classify.ClassificationStatus;
import com.unirag.rag.classify.QueryIntent;
import com.unirag.rag.model.RagRequest;
import com.unirag.rag.model.RagResult;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.StageContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RagRouter 候选顺序、能力判定与单次执行验收。 */
class RagRouterTest {

    private static final String TENANT = "router-test";

    @Test
    void 重复注册同一范式时启动失败() {
        assertThatThrownBy(() -> new RagCapabilityRegistry(List.of(
                executor(RagParadigm.TRADITIONAL_RAG, true, true),
                executor(RagParadigm.TRADITIONAL_RAG, true, true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate RAG paradigm");
    }

    @Test
    void 简单事实直接选择传统RAG() {
        RagCapabilityRegistry registry = new RagCapabilityRegistry(List.of(
                executor(RagParadigm.TRADITIONAL_RAG, true, true)));
        RagRouter router = new RagRouter(registry);
        RecordingExecutor traditional = recording(registry, RagParadigm.TRADITIONAL_RAG);

        RagRoute route = router.route(TENANT, outcome(ClassificationStatus.SUCCESS,
                QueryIntent.SIMPLE_FACT, 0.91, "RULE_MATCH"));

        assertThat(route.decision().targetParadigm()).isEqualTo(RagParadigm.TRADITIONAL_RAG);
        assertThat(route.decision().actualParadigm()).isEqualTo(RagParadigm.TRADITIONAL_RAG);
        assertThat(route.decision().degraded()).isFalse();
        assertThat(route.decision().reasonCodes()).isEmpty();
        assertThat(route.executor()).isSameAs(traditional);
    }

    @Test
    void 多跳问题按图Agentic传统顺序选择首个可用能力() {
        RagRouter onlyTraditional = new RagRouter(new RagCapabilityRegistry(List.of(
                executor(RagParadigm.TRADITIONAL_RAG, true, true))));
        RagRoute fallback = onlyTraditional.route(TENANT, success(QueryIntent.MULTI_HOP_RELATION));
        assertThat(fallback.decision().targetParadigm()).isEqualTo(RagParadigm.GRAPH_RAG);
        assertThat(fallback.decision().actualParadigm()).isEqualTo(RagParadigm.TRADITIONAL_RAG);
        assertThat(fallback.decision().reasonCodes())
                .containsExactly("CAPABILITY_NOT_REGISTERED", "CAPABILITY_NOT_REGISTERED");

        RagRouter agentReady = new RagRouter(new RagCapabilityRegistry(List.of(
                executor(RagParadigm.TRADITIONAL_RAG, true, true),
                disabled(RagParadigm.GRAPH_RAG),
                executor(RagParadigm.AGENTIC_RAG, true, true))));
        RagRoute viaAgent = agentReady.route(TENANT, success(QueryIntent.MULTI_HOP_RELATION));
        assertThat(viaAgent.decision().actualParadigm()).isEqualTo(RagParadigm.AGENTIC_RAG);
        assertThat(viaAgent.decision().reasonCodes()).containsExactly("CAPABILITY_DISABLED");
    }

    @Test
    void 复杂问题优先Agentic失败时降级传统() {
        RagRouter router = new RagRouter(new RagCapabilityRegistry(List.of(
                executor(RagParadigm.TRADITIONAL_RAG, true, true))));
        RagRoute route = router.route(TENANT, success(QueryIntent.COMPLEX_REASONING));

        assertThat(route.decision().targetParadigm()).isEqualTo(RagParadigm.AGENTIC_RAG);
        assertThat(route.decision().actualParadigm()).isEqualTo(RagParadigm.TRADITIONAL_RAG);
        assertThat(route.decision().reasonCodes()).containsExactly("CAPABILITY_NOT_REGISTERED");
    }

    @Test
    void 低置信度与分类失败以Agentic为目标并安全降级() {
        RagRouter router = new RagRouter(new RagCapabilityRegistry(List.of(
                executor(RagParadigm.TRADITIONAL_RAG, true, true))));
        RagRoute low = router.route(TENANT, outcome(ClassificationStatus.LOW_CONFIDENCE,
                QueryIntent.MULTI_HOP_RELATION, 0.69, "LOW_CONFIDENCE"));
        RagRoute failed = router.route(TENANT, new ClassificationOutcome(
                ClassificationStatus.FAILED, null, null, List.of(), "CLASSIFICATION_TIMEOUT",
                null, 205));

        assertThat(low.decision().targetParadigm()).isEqualTo(RagParadigm.AGENTIC_RAG);
        assertThat(low.decision().actualParadigm()).isEqualTo(RagParadigm.TRADITIONAL_RAG);
        assertThat(low.decision().reasonCodes())
                .containsExactly("LOW_CONFIDENCE", "CAPABILITY_NOT_REGISTERED");
        assertThat(failed.decision().targetParadigm()).isEqualTo(RagParadigm.AGENTIC_RAG);
        assertThat(failed.decision().actualParadigm()).isEqualTo(RagParadigm.TRADITIONAL_RAG);
        assertThat(failed.decision().reasonCodes())
                .containsExactly("CLASSIFICATION_TIMEOUT", "CAPABILITY_NOT_REGISTERED");
    }

    @Test
    void 健康检查异常按不可用处理且每次路由只返回一个执行器() {
        RagCapabilityRegistry registry = new RagCapabilityRegistry(List.of(
                throwing(RagParadigm.GRAPH_RAG),
                executor(RagParadigm.TRADITIONAL_RAG, true, true)));
        RagRouter router = new RagRouter(registry);
        RecordingExecutor traditional = recording(registry, RagParadigm.TRADITIONAL_RAG);

        RagRoute route = router.route(TENANT, success(QueryIntent.MULTI_HOP_RELATION));
        route.executor().execute(request(), context());

        assertThat(route.decision().capabilityProbes()).anySatisfy(probe -> {
            assertThat(probe.paradigm()).isEqualTo(RagParadigm.GRAPH_RAG);
            assertThat(probe.state()).isEqualTo(CapabilityAvailability.UNHEALTHY);
            assertThat(probe.reasonCode()).isEqualTo("CAPABILITY_UNHEALTHY");
        });
        assertThat(route.decision().reasonCodes())
                .containsExactly("CAPABILITY_UNHEALTHY", "CAPABILITY_NOT_REGISTERED");
        assertThat(traditional.executions.get()).isEqualTo(1);
        assertThat(route.executor().paradigm()).isEqualTo(route.decision().actualParadigm());
    }

    private static ClassificationOutcome success(QueryIntent intent) {
        return outcome(ClassificationStatus.SUCCESS, intent, 0.91, "RULE_MATCH");
    }

    private static ClassificationOutcome outcome(ClassificationStatus status, QueryIntent intent,
                                                 double confidence, String reasonCode) {
        return new ClassificationOutcome(status, intent, confidence,
                List.of(new ClassificationEvidence("TEST_RULE", intent, 0.55, "test evidence")),
                reasonCode, "rules-v1", 3);
    }

    private static RecordingExecutor recording(RagCapabilityRegistry registry, RagParadigm paradigm) {
        return (RecordingExecutor) registry.executor(paradigm).orElseThrow();
    }

    private static RagRequest request() {
        return new RagRequest("什么是租户隔离？", null, 5, 0.0, null, null, null);
    }

    private static StageContext context() {
        return new StageContext("0123456789abcdef0123456789abcdef",
                StageResult.KIND_QA, TENANT, 1L, null, "什么是租户隔离？", null);
    }

    private static RagParadigmExecutor executor(RagParadigm paradigm, boolean enabled, boolean healthy) {
        return new RecordingExecutor(paradigm, enabled, healthy);
    }

    private static RagParadigmExecutor disabled(RagParadigm paradigm) {
        return new RecordingExecutor(paradigm, false, true);
    }

    private static RagParadigmExecutor throwing(RagParadigm paradigm) {
        return new RecordingExecutor(paradigm, true, true) {
            @Override
            public boolean healthyFor(String tenantCode) {
                throw new IllegalStateException("health probe failed");
            }
        };
    }

    private static class RecordingExecutor implements RagParadigmExecutor {
        private final RagParadigm paradigm;
        private final boolean enabled;
        private final boolean healthy;
        private final AtomicInteger executions = new AtomicInteger();

        private RecordingExecutor(RagParadigm paradigm, boolean enabled, boolean healthy) {
            this.paradigm = paradigm;
            this.enabled = enabled;
            this.healthy = healthy;
        }

        @Override
        public RagParadigm paradigm() {
            return paradigm;
        }

        @Override
        public boolean enabledFor(String tenantCode) {
            return enabled;
        }

        @Override
        public boolean healthyFor(String tenantCode) {
            return healthy;
        }

        @Override
        public RagResult execute(RagRequest request, StageContext ctx) {
            executions.incrementAndGet();
            return new RagResult("回答", List.of(), false, null);
        }
    }
}

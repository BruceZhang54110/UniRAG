package com.unirag.retrieval;

import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.pipeline.DefaultPipeline;
import com.unirag.retrieval.pipeline.EnhancementExtension;
import com.unirag.retrieval.pipeline.FailurePolicy;
import com.unirag.retrieval.pipeline.ObservationExtension;
import com.unirag.retrieval.pipeline.ShortCircuit;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageFailureException;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 扩展机制契约测试（T013）：逐条覆盖 contracts/pipeline-extension.md §7 的 5 项可验证要点。
 *
 * <p>用测试专用两阶段流水线（String → List&lt;String&gt; → String）承载，避免与真实检索阶段耦合；
 * 观察型探针以 Bean 形式注册，证明"加一个观察型扩展即可接入，无需改动阶段实现"。
 */
class PipelineExtensionContractIT extends AbstractRetrievalIT {

    private static final String TENANT = "extContract";

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        ProbeObservationExtension probeObservationExtension() {
            return new ProbeObservationExtension();
        }
    }

    /** 探针：记录每个阶段的输入/输出类型，并证明共享数据可经 StageContext 传递。 */
    static class ProbeObservationExtension implements ObservationExtension {

        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void beforeStage(StageName stage, Object input, StageContext ctx) {
            calls.add(stage + ":before:" + typeName(input));
            ctx.put("probe-stage", stage);
        }

        @Override
        public void afterStage(StageName stage, Object output, StageOutcome outcome, StageContext ctx) {
            calls.add(stage + ":after:" + typeName(output) + ":" + outcome.status());
        }

        void reset() {
            calls.clear();
        }
    }

    static class RecordingObservationExtension implements ObservationExtension {

        final List<String> outcomes = new ArrayList<>();

        @Override
        public void beforeStage(StageName stage, Object input, StageContext ctx) {
        }

        @Override
        public void afterStage(StageName stage, Object output, StageOutcome outcome, StageContext ctx) {
            outcomes.add(stage + ":" + outcome.status());
        }
    }

    static class ThrowingObservationExtension extends RecordingObservationExtension {

        @Override
        public void beforeStage(StageName stage, Object input, StageContext ctx) {
            throw new IllegalStateException("observation exploded");
        }

        @Override
        public void afterStage(StageName stage, Object output, StageOutcome outcome, StageContext ctx) {
            throw new IllegalStateException("observation exploded");
        }
    }

    /** 顺序探针：在第二阶段把标记追加进输入列表，据此观察执行顺序。 */
    static class OrderProbeEnhancement implements EnhancementExtension {

        private final String mark;
        private final int order;

        OrderProbeEnhancement(String mark, int order) {
            this.mark = mark;
            this.order = order;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public Object beforeStage(StageName stage, Object input, StageContext ctx) {
            if (stage == StageName.VECTOR_SEARCH) {
                marks(input).add(mark);
            }
            return input;
        }

        @Override
        public Object afterStage(StageName stage, Object output, StageContext ctx) {
            return output;
        }

        @Override
        public FailurePolicy failurePolicy() {
            return FailurePolicy.FAIL_OPEN;
        }

        @SuppressWarnings("unchecked")
        private static List<String> marks(Object input) {
            return (List<String>) input;
        }
    }

    /** 重排类增强：FAIL_OPEN —— 失败时仍应返回未重排的结果（§4）。 */
    static class FailingRerankEnhancement implements EnhancementExtension {

        @Override
        public int order() {
            return 1;
        }

        @Override
        public Object beforeStage(StageName stage, Object input, StageContext ctx) {
            return input;
        }

        @Override
        public Object afterStage(StageName stage, Object output, StageContext ctx) {
            if (stage == StageName.VECTOR_SEARCH) {
                throw new IllegalStateException("rerank unavailable");
            }
            return output;
        }

        @Override
        public FailurePolicy failurePolicy() {
            return FailurePolicy.FAIL_OPEN;
        }
    }

    /** 改写查询类增强：FAIL_CLOSED —— 失败即该阶段失败并进入降级（§4）。 */
    static class FailingRewriteEnhancement implements EnhancementExtension {

        @Override
        public int order() {
            return 1;
        }

        @Override
        public Object beforeStage(StageName stage, Object input, StageContext ctx) {
            if (stage == StageName.VECTOR_SEARCH) {
                throw new IllegalStateException("query rewrite unavailable");
            }
            return input;
        }

        @Override
        public Object afterStage(StageName stage, Object output, StageContext ctx) {
            return output;
        }

        @Override
        public FailurePolicy failurePolicy() {
            return FailurePolicy.FAIL_CLOSED;
        }
    }

    /** 短路演示：命中缓存时跳过后续阶段，直接以携带值作为整条流水线的结果（§3.2）。 */
    static class ShortCircuitEnhancement implements EnhancementExtension {

        @Override
        public int order() {
            return 1;
        }

        @Override
        public Object beforeStage(StageName stage, Object input, StageContext ctx) {
            return input;
        }

        @Override
        public Object afterStage(StageName stage, Object output, StageContext ctx) {
            if (stage == StageName.QUERY_EMBEDDING) {
                throw new ShortCircuit("cached");
            }
            return output;
        }

        @Override
        public FailurePolicy failurePolicy() {
            return FailurePolicy.FAIL_OPEN;
        }
    }

    private static final Stage<String, List<String>> SPLIT = new Stage<>() {
        @Override
        public StageName name() {
            return StageName.QUERY_EMBEDDING;
        }

        @Override
        public List<String> execute(String input, StageContext ctx) {
            return new ArrayList<>(List.of(input, input.toUpperCase()));
        }
    };

    private static final Stage<List<String>, String> JOIN = new Stage<>() {
        @Override
        public StageName name() {
            return StageName.VECTOR_SEARCH;
        }

        @Override
        public String execute(List<String> input, StageContext ctx) {
            return String.join("|", input);
        }
    };

    private static final List<Stage<?, ?>> CHAIN = List.of(SPLIT, JOIN);

    @Autowired
    private DefaultPipeline springPipeline;

    @Autowired
    private ProbeObservationExtension probe;

    private final DefaultPipeline barePipeline = new DefaultPipeline(List.of(), Map.of());

    @BeforeEach
    void setUp() {
        cleanupObservations(TENANT);
        insertTenant(TENANT);
        probe.reset();
    }

    @Test
    void 新增观察型扩展Bean即被调用且不改变结果() {
        String withProbe = run(springPipeline);
        String withoutProbe = run(barePipeline);

        assertThat(withProbe).isEqualTo(withoutProbe).isEqualTo("alpha|ALPHA");
        assertThat(probe.calls).containsExactly(
                "QUERY_EMBEDDING:before:String",
                "QUERY_EMBEDDING:after:ArrayList:SUCCESS",
                "VECTOR_SEARCH:before:ArrayList",
                "VECTOR_SEARCH:after:String:SUCCESS");
    }

    @Test
    void 观察型扩展抛异常时结果与未注册该扩展完全一致() {
        DefaultPipeline pipeline = new DefaultPipeline(List.of(new ThrowingObservationExtension()), Map.of());

        assertThat(run(pipeline)).isEqualTo(run(barePipeline)).isEqualTo("alpha|ALPHA");
    }

    @Test
    void 增强型扩展order反转注册顺序不影响执行顺序() {
        Map<String, EnhancementExtension> reversed = new LinkedHashMap<>();
        reversed.put("secondExtension", new OrderProbeEnhancement("second", 2));
        reversed.put("firstExtension", new OrderProbeEnhancement("first", 1));

        assertThat(run(new DefaultPipeline(List.of(), reversed))).isEqualTo("alpha|ALPHA|first|second");
    }

    @Test
    void order相同时按Bean名兜底排序() {
        Map<String, EnhancementExtension> sameOrder = new LinkedHashMap<>();
        sameOrder.put("zetaExtension", new OrderProbeEnhancement("zeta", 5));
        sameOrder.put("alphaExtension", new OrderProbeEnhancement("alpha", 5));

        assertThat(run(new DefaultPipeline(List.of(), sameOrder))).isEqualTo("alpha|ALPHA|alpha|zeta");
    }

    @Test
    void failClosed增强型失败时该阶段记为失败() {
        RecordingObservationExtension observer = new RecordingObservationExtension();
        DefaultPipeline pipeline = new DefaultPipeline(List.of(observer),
                Map.of("rewriteExtension", new FailingRewriteEnhancement()));

        assertThatThrownBy(() -> run(pipeline))
                .isInstanceOfSatisfying(StageFailureException.class,
                        e -> assertThat(e.stage()).isEqualTo(StageName.VECTOR_SEARCH));
        assertThat(observer.outcomes).containsExactly("QUERY_EMBEDDING:SUCCESS", "VECTOR_SEARCH:FAILED");
    }

    @Test
    void failOpen重排扩展失败时仍返回未重排结果() {
        DefaultPipeline pipeline = new DefaultPipeline(List.of(),
                Map.of("rerankExtension", new FailingRerankEnhancement()));

        assertThat(run(pipeline)).isEqualTo("alpha|ALPHA");
    }

    @Test
    void 增强型扩展可短路并以携带值结束流水线() {
        DefaultPipeline pipeline = new DefaultPipeline(List.of(),
                Map.of("cacheExtension", new ShortCircuitEnhancement()));

        assertThat(run(pipeline)).isEqualTo("cached");
    }

    private String run(DefaultPipeline pipeline) {
        return (String) pipeline.run(CHAIN, "alpha", new StageContext(
                "trace-probe", StageResult.KIND_RETRIEVAL, TENANT, tenantId(TENANT),
                null, "probe query", null));
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}

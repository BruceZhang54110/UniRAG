package com.unirag.retrieval.query;

import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.retrieval.model.StageResult;
import com.unirag.retrieval.observation.ObservationMetrics;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.retrieval.pipeline.StageStatus;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueryRewriteEnhancementTest {

    private static final String TENANT = "rewriteUnit";

    private static final String ORIGINAL = "那个把文件切块的玩意儿怎么配？";

    private TenantResourceRouter router;

    private QueryRewriteSupport support;

    private ObservationMetrics metrics;

    private QueryTransformer transformer;

    private QueryRewriteEnhancement enhancement;

    @BeforeEach
    void setUp() {
        router = mock(TenantResourceRouter.class);
        support = mock(QueryRewriteSupport.class);
        metrics = mock(ObservationMetrics.class);
        transformer = mock(QueryTransformer.class);
        enhancement = new QueryRewriteEnhancement(router, support, properties(false), metrics);
        when(support.primaryKey(TENANT)).thenReturn(Optional.of("rewriteModel"));
        when(router.getPrimary(eq(TENANT), eq(ResourceType.QUERY_REWRITE_MODEL),
                eq(QueryTransformer.class))).thenReturn(transformer);
    }

    @Test
    void 成功时只替换检索查询并保留原问题() {
        when(transformer.transform(any(Query.class)))
                .thenReturn(List.of(Query.from("文档切分配置")));

        StageContext ctx = ctx();
        Object output = enhancement.beforeStage(StageName.QUERY_EMBEDDING, request(true), ctx);

        RetrievalRequest rewritten = (RetrievalRequest) output;
        assertThat(rewritten.query()).isEqualTo(ORIGINAL);
        assertThat(rewritten.retrievalQuery()).isEqualTo("文档切分配置");
        assertThat(rewritten.rewrite()).isTrue();
        assertThat(ctx.get(QueryRewriteOutcome.CONTEXT_KEY, QueryRewriteOutcome.class)).isNotNull();
    }

    @Test
    void 成功时写上下文结果与指标() {
        StageContext ctx = ctx();
        when(transformer.transform(any(Query.class)))
                .thenReturn(List.of(Query.from("文档切分配置")));

        enhancement.beforeStage(StageName.QUERY_EMBEDDING, request(true), ctx);

        QueryRewriteOutcome outcome = ctx.get(QueryRewriteOutcome.CONTEXT_KEY, QueryRewriteOutcome.class);
        assertThat(outcome).isNotNull();
        assertThat(outcome.status()).isEqualTo(StageStatus.SUCCESS);
        assertThat(outcome.modelKey()).isEqualTo("rewriteModel");
        assertThat(outcome.originalChars()).isEqualTo(ORIGINAL.length());
        assertThat(outcome.retrievalChars()).isEqualTo("文档切分配置".length());
        assertThat(outcome.shadow()).isFalse();
        assertThat(outcome.detail()).contains("originalChars=").contains("preview=");
        verify(metrics).recordQueryRewrite(eq(TENANT), eq(StageStatus.SUCCESS), any(Duration.class));
    }

    @Test
    void 请求关闭时不调用改写() {
        RetrievalRequest input = request(false);

        assertThat(enhancement.beforeStage(StageName.QUERY_EMBEDDING, input, ctx())).isSameAs(input);
        verifyNoInteractions(transformer, metrics);
    }

    @Test
    void 原问题超长时跳过改写() {
        QueryRewriteEnhancement shortLimit = new QueryRewriteEnhancement(router, support,
                new QueryRewriteProperties(true, false, 500, 2.0, 4, 120), metrics);

        Object output = shortLimit.beforeStage(StageName.QUERY_EMBEDDING, request(true), ctx());

        assertThat(output).isInstanceOf(RetrievalRequest.class);
        assertThat(((RetrievalRequest) output).retrievalQuery()).isEqualTo(ORIGINAL);
        verifyNoInteractions(transformer, metrics);
    }

    @Test
    void 改写异常时回退原问题并记录降级() {
        StageContext ctx = ctx();
        when(transformer.transform(any(Query.class))).thenThrow(new IllegalStateException("down"));

        Object output = enhancement.beforeStage(StageName.QUERY_EMBEDDING, request(true), ctx);

        assertThat(((RetrievalRequest) output).retrievalQuery()).isEqualTo(ORIGINAL);
        QueryRewriteOutcome outcome = ctx.get(QueryRewriteOutcome.CONTEXT_KEY, QueryRewriteOutcome.class);
        assertThat(outcome.status()).isEqualTo(StageStatus.DEGRADED);
        assertThat(outcome.reason()).contains("IllegalStateException");
        verify(metrics).recordQueryRewrite(eq(TENANT), eq(StageStatus.DEGRADED), any(Duration.class));
    }

    @Test
    void 空输出回退并记录原因() {
        assertRejected(List.of(), RewriteValidator.EMPTY_OUTPUT);
    }

    @Test
    void 多查询回退并记录原因() {
        assertRejected(List.of(Query.from("a"), Query.from("b")), RewriteValidator.MULTIPLE_QUERIES);
    }

    @Test
    void 多行输出回退并记录原因() {
        assertRejected(List.of(Query.from("第一行\n第二行")), RewriteValidator.MULTILINE_OUTPUT);
    }

    @Test
    void 超长输出回退并记录原因() {
        QueryRewriteEnhancement shortLimit = new QueryRewriteEnhancement(router, support,
                new QueryRewriteProperties(true, false, 10, 10.0, 2000, 120), metrics);
        when(transformer.transform(any(Query.class)))
                .thenReturn(List.of(Query.from("12345678901")));

        Object output = shortLimit.beforeStage(StageName.QUERY_EMBEDDING, request(true), ctx());

        assertThat(((RetrievalRequest) output).retrievalQuery()).isEqualTo(ORIGINAL);
        verify(metrics).recordQueryRewrite(eq(TENANT), eq(StageStatus.DEGRADED), any(Duration.class));
    }

    @Test
    void 丢失数字约束时回退并记录原因() {
        RetrievalRequest input = new RetrievalRequest("2026-10-19 发布", null, null, 5, 0.0, 5, false, true);
        StageContext ctx = ctx("2026-10-19 发布");
        when(transformer.transform(any(Query.class)))
                .thenReturn(List.of(Query.from("发布日期")));

        Object output = enhancement.beforeStage(StageName.QUERY_EMBEDDING, input, ctx);

        assertThat(((RetrievalRequest) output).retrievalQuery()).isEqualTo("2026-10-19 发布");
        QueryRewriteOutcome outcome = ctx.get(QueryRewriteOutcome.CONTEXT_KEY, QueryRewriteOutcome.class);
        assertThat(outcome.reason()).isEqualTo(RewriteValidator.DROPPED_CONSTRAINT);
    }

    @Test
    void 观察模式不改输入但记录改写结果() {
        QueryRewriteEnhancement shadow = new QueryRewriteEnhancement(router, support,
                properties(true), metrics);
        StageContext ctx = ctx();
        when(transformer.transform(any(Query.class)))
                .thenReturn(List.of(Query.from("文档切分配置")));

        Object output = shadow.beforeStage(StageName.QUERY_EMBEDDING, request(true), ctx);

        assertThat(((RetrievalRequest) output).retrievalQuery()).isEqualTo(ORIGINAL);
        QueryRewriteOutcome outcome = ctx.get(QueryRewriteOutcome.CONTEXT_KEY, QueryRewriteOutcome.class);
        assertThat(outcome.shadow()).isTrue();
        assertThat(outcome.status()).isEqualTo(StageStatus.SUCCESS);
        assertThat(outcome.detail()).contains("shadow=true");
    }

    @Test
    void afterStage原样返回输出() {
        Object output = new Object();
        assertThat(enhancement.afterStage(StageName.QUERY_EMBEDDING, output, ctx())).isSameAs(output);
    }

    private void assertRejected(List<Query> transformed, String expectedReason) {
        StageContext ctx = ctx();
        when(transformer.transform(any(Query.class))).thenReturn(transformed);

        Object output = enhancement.beforeStage(StageName.QUERY_EMBEDDING, request(true), ctx);

        assertThat(((RetrievalRequest) output).retrievalQuery()).isEqualTo(ORIGINAL);
        QueryRewriteOutcome outcome = ctx.get(QueryRewriteOutcome.CONTEXT_KEY, QueryRewriteOutcome.class);
        assertThat(outcome.status()).isEqualTo(StageStatus.DEGRADED);
        assertThat(outcome.reason()).isEqualTo(expectedReason);
        verify(metrics, never()).recordQueryRewrite(eq(TENANT), eq(StageStatus.SUCCESS), any(Duration.class));
    }

    private static RetrievalRequest request(boolean rewrite) {
        return new RetrievalRequest(ORIGINAL, null, null, 5, 0.0, 5, false, rewrite);
    }

    private static StageContext ctx() {
        return ctx(ORIGINAL);
    }

    private static StageContext ctx(String query) {
        return new StageContext("trace", StageResult.KIND_RETRIEVAL, TENANT, 1L, null, query, null);
    }

    private static QueryRewriteProperties properties(boolean shadow) {
        return new QueryRewriteProperties(true, shadow, 500, 2.0, 2000, 120);
    }
}

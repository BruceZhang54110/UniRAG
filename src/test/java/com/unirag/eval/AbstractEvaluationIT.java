package com.unirag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.AbstractPostgresIntegrationTest;
import com.unirag.eval.dataset.DatasetLoader;
import com.unirag.eval.dataset.DatasetValidator;
import com.unirag.eval.metric.FailureAttributor;
import com.unirag.eval.metric.RetrievalMetrics;
import com.unirag.eval.report.ReportWriter;
import com.unirag.eval.retrieval.EvaluationRetriever;
import com.unirag.eval.setup.EvalTenantProvisioner;
import com.unirag.eval.setup.EvalTenantResourceRouter;
import com.unirag.eval.setup.EvaluationIndexPreparer;
import com.unirag.ingestion.IngestionService;
import com.unirag.retrieval.RetrievalService;
import com.unirag.retrieval.stage.MergeStage;
import com.unirag.retrieval.stage.QueryEmbeddingStage;
import com.unirag.retrieval.stage.VectorSearchStage;
import com.unirag.tenant.resource.TenantResourceIndexRegistry;
import com.unirag.tenant.resource.TenantResourceRegistrar;
import com.unirag.common.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 评测集成测试基类。
 *
 * <p>不继承 {@code AbstractRetrievalIT}，因此不引入 {@code @MockBean TenantResourceRouter}；
 * 使用真实的 {@link EvalTenantResourceRouter}（父类为真实 Router）与真实 Store/摄取/检索服务。
 */
@Import(AbstractEvaluationIT.EvalRouterConfiguration.class)
public abstract class AbstractEvaluationIT extends AbstractPostgresIntegrationTest {

    @Autowired
    protected IngestionService ingestionService;

    @Autowired
    protected RetrievalService retrievalService;

    @Autowired
    protected QueryEmbeddingStage queryEmbeddingStage;

    @Autowired
    protected VectorSearchStage vectorSearchStage;

    @Autowired
    protected MergeStage mergeStage;

    @Autowired
    protected TenantContext tenantContext;

    @Autowired
    protected Environment environment;

    @Autowired
    protected TenantResourceRegistrar registrar;

    @Autowired
    protected ApplicationContext applicationContext;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected EvalTenantResourceRouter tenantResourceRouter;

    protected EvaluationRunner runner() {
        return new EvaluationRunner(
                new DatasetLoader(objectMapper),
                new DatasetValidator(),
                new EvalTenantProvisioner(jdbcTemplate, environment, registrar, applicationContext, objectMapper),
                new EvaluationIndexPreparer(ingestionService),
                new EvaluationRetriever(retrievalService, queryEmbeddingStage, vectorSearchStage, mergeStage,
                        tenantContext),
                new RetrievalMetrics(),
                new FailureAttributor(),
                new ReportWriter(objectMapper),
                tenantResourceRouter,
                jdbcTemplate,
                objectMapper);
    }

    @TestConfiguration
    static class EvalRouterConfiguration {

        @Bean
        @Primary
        EvalTenantResourceRouter evalTenantResourceRouter(GenericApplicationContext applicationContext,
                                                          TenantResourceIndexRegistry indexRegistry) {
            return new EvalTenantResourceRouter(applicationContext, indexRegistry);
        }
    }
}

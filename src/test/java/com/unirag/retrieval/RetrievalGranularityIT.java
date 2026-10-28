package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import com.unirag.ingestion.IngestionTestDocuments;
import com.unirag.retrieval.model.RetrievalRequest;
import com.unirag.common.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US1 粒度语义验收（spec.md FR-003、边界情况，contracts/retrieval-api.md §2）：
 * 指定粒度只检索对应表、不存在的粒度明确失败并列出可用粒度（不得静默退化为全量）、
 * 无启用管线时返回空结果不报错。
 *
 * <p>租户码全局唯一：{@code retrGran} / {@code retrGranEmpty}。
 */
class RetrievalGranularityIT extends AbstractRetrievalIT {

    private static final String TENANT = "retrGran";
    private static final String EMPTY_TENANT = "retrGranEmpty";
    private static final String COARSE_TABLE = "embeddings_retrgran_coarse";
    private static final String FINE_TABLE = "embeddings_retrgran_fine";

    @Autowired
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        cleanupTenant(TENANT, COARSE_TABLE, FINE_TABLE);
        cleanupObservations(TENANT);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "coarseStore", COARSE_TABLE, 1024);
        insertStoreConfig(tenantId, "fineStore", FINE_TABLE, 1024);
        insertPipeline(tenantId, "coarse", 500, 50, "coarseStore", "default");
        insertPipeline(tenantId, "fine", 150, 20, "fineStore", "default");
        registrar.loadAndRegister();
        stubRouter(TENANT, Map.of("default", new DeterministicEmbeddingModel(1024)),
                Map.of("coarseStore", storeBean(TENANT, "coarseStore"),
                        "fineStore", storeBean(TENANT, "fineStore")));
        ingestionService.ingest(new IngestRequest(
                TENANT, "granularity-doc", null, IngestionTestDocuments.longChineseDocument()));

        cleanupTenant(EMPTY_TENANT);
        cleanupObservations(EMPTY_TENANT);
        insertTenant(EMPTY_TENANT);
    }

    @Test
    void 指定粒度只检索对应表() {
        RetrievalService.RetrievalResult result = retrieve(new RetrievalRequest(
                "向量数据库", "coarse", null, 5, 0.0));

        assertThat(result.searchedTables()).isEqualTo(1);
        assertThat(result.failedTables()).isEmpty();
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.pipelineKey()).isEqualTo("coarse"));
    }

    @Test
    void 粒度也可用向量存储资源key指定() {
        RetrievalService.RetrievalResult result = retrieve(new RetrievalRequest(
                "向量数据库", "fineStore", null, 5, 0.0));

        assertThat(result.searchedTables()).isEqualTo(1);
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.pipelineKey()).isEqualTo("fine"));
    }

    @Test
    void 不存在的粒度明确失败并列出可用粒度() {
        assertThatThrownBy(() -> retrieve(new RetrievalRequest("向量数据库", "ghost", null, 5, 0.0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("GRANULARITY_NOT_FOUND");
                    assertThat(e.getDetails().toString()).contains("coarse").contains("fine");
                });
    }

    @Test
    void 未启用的管线不可作为粒度() {
        jdbcTemplate.update("UPDATE ingestion_pipeline SET enabled = FALSE WHERE tenant_id = ? AND pipeline_key = ?",
                tenantId(TENANT), "coarse");

        assertThatThrownBy(() -> retrieve(new RetrievalRequest("向量数据库", "coarse", null, 5, 0.0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("GRANULARITY_NOT_FOUND");
                    assertThat(e.getDetails().toString()).contains("fine").doesNotContain("coarse");
                });
    }

    @Test
    void 未指定粒度时检索全部已启用管线并归并() {
        RetrievalService.RetrievalResult result = retrieve(new RetrievalRequest("向量数据库", null, null, 10, 0.0));

        assertThat(result.searchedTables()).isEqualTo(2);
        assertThat(result.failedTables()).isEmpty();
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().stream().map(chunk -> chunk.pipelineKey()).distinct())
                .containsExactlyInAnyOrder("coarse", "fine");
    }

    @Test
    void 租户无启用管线时返回空结果不报错() {
        RetrievalService.RetrievalResult result = inTenant(EMPTY_TENANT,
                () -> retrievalService.retrieve(new RetrievalRequest("向量数据库", null, null, 5, 0.0)));

        assertThat(result.chunks()).isEmpty();
        assertThat(result.searchedTables()).isZero();
        assertThat(result.failedTables()).isEmpty();
    }

    private RetrievalService.RetrievalResult retrieve(RetrievalRequest request) {
        return inTenant(TENANT, () -> retrievalService.retrieve(request));
    }
}

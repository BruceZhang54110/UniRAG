package com.unirag.retrieval;

import com.unirag.ingestion.DeterministicEmbeddingModel;
import com.unirag.ingestion.IngestRequest;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 查询改写集成测试基类：装配一个有改写资源的租户和一个无改写资源的租户，
 * 用真实 PostgreSQL + pgvector 与确定性嵌入/聊天/改写替身。
 */
abstract class AbstractQueryRewriteIT extends AbstractRetrievalIT {

    protected static final String TENANT = "rewriteApi";

    protected static final String TABLE = "embeddings_rewriteapi_main";

    protected static final String NONE_TENANT = "rewriteNone";

    protected static final String NONE_TABLE = "embeddings_rewritenone_main";

    protected static final String ORIGINAL = "那个把文件切块的玩意儿怎么配？";

    protected static final String REWRITTEN = "文档切分配置方法";

    protected static final String DOC_NAME = "切分配置";

    protected static final String DOC_TEXT =
            "文档切分配置方法：在摄取管线中设置 max-segment-tokens 与 overlap-tokens。";

    protected static final String NONE_QUERY = "普通问题";

    protected OpenAiChatModel chatModel;

    protected AtomicReference<String> lastEmbeddingText;

    protected AtomicReference<String> noneLastEmbeddingText;

    @BeforeEach
    void setUpRewriteTenants() {
        lastEmbeddingText = new AtomicReference<>();
        cleanupTenant(TENANT, TABLE);
        cleanupObservations(TENANT);
        long tenantId = insertTenant(TENANT);
        insertStoreConfig(tenantId, "mainStore", TABLE, 1024);
        insertPipeline(tenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        EmbeddingModel embedding = recordingEmbeddingModel(
                new DeterministicEmbeddingModel(1024), lastEmbeddingText);
        stubRouter(TENANT, Map.of("default", embedding),
                Map.of("mainStore", storeBean(TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(TENANT, DOC_NAME, null, DOC_TEXT));
        chatModel = stubChatModel(TENANT);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse("回答。[1]"));

        noneLastEmbeddingText = new AtomicReference<>();
        cleanupTenant(NONE_TENANT, NONE_TABLE);
        cleanupObservations(NONE_TENANT);
        long noneTenantId = insertTenant(NONE_TENANT);
        insertStoreConfig(noneTenantId, "mainStore", NONE_TABLE, 1024);
        insertPipeline(noneTenantId, "main", 300, 30, "mainStore", "default");
        registrar.loadAndRegister();
        EmbeddingModel noneEmbedding = recordingEmbeddingModel(
                new DeterministicEmbeddingModel(1024), noneLastEmbeddingText);
        stubRouter(NONE_TENANT, Map.of("default", noneEmbedding),
                Map.of("mainStore", storeBean(NONE_TENANT, "mainStore")));
        ingestionService.ingest(new IngestRequest(NONE_TENANT, "普通文档", null,
                "没有查询改写配置的租户文档。"));
        OpenAiChatModel noneChatModel = stubChatModel(NONE_TENANT);
        when(noneChatModel.chat(any(ChatRequest.class))).thenReturn(chatResponse("回答。[1]"));
    }
}

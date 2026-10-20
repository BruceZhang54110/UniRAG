package com.unirag.eval.setup;

import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceIndexRegistry;
import com.unirag.tenant.resource.TenantResourceRouter;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.context.support.GenericApplicationContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评测专用 Router：真实继承 {@link TenantResourceRouter}，只对评测模型做显式覆盖。
 *
 * <p>不依赖 Mockito：Store 等资源仍走父类的真实索引与 Bean 解析；EMBEDDING_MODEL 与
 * RERANK_MODEL 在评测运行器明确覆盖时返回源租户的真实模型或 replay/recording 包装。
 */
public class EvalTenantResourceRouter extends TenantResourceRouter {

    private final Map<String, EmbeddingModel> embeddingModels = new ConcurrentHashMap<>();

    private final Map<String, ScoringModel> rerankModels = new ConcurrentHashMap<>();

    public EvalTenantResourceRouter(GenericApplicationContext applicationContext,
                                    TenantResourceIndexRegistry indexRegistry) {
        super(applicationContext, indexRegistry);
    }

    public void overrideEmbeddingModel(String tenantCode, String resourceKey, EmbeddingModel model) {
        embeddingModels.put(key(tenantCode, resourceKey), model);
    }

    public void overrideRerankModel(String tenantCode, ScoringModel model) {
        rerankModels.put(tenantCode, model);
    }

    @Override
    public <T> T get(String tenantCode, ResourceType type, String resourceKey, Class<T> beanType) {
        if (type == ResourceType.EMBEDDING_MODEL) {
            EmbeddingModel model = embeddingModels.get(key(tenantCode, resourceKey));
            if (model != null) {
                return beanType.cast(model);
            }
        }
        return super.get(tenantCode, type, resourceKey, beanType);
    }

    @Override
    public boolean hasPrimary(String tenantCode, ResourceType type) {
        if (type == ResourceType.RERANK_MODEL && rerankModels.containsKey(tenantCode)) {
            return true;
        }
        return super.hasPrimary(tenantCode, type);
    }

    @Override
    public <T> T getPrimary(String tenantCode, ResourceType type, Class<T> beanType) {
        if (type == ResourceType.RERANK_MODEL) {
            ScoringModel model = rerankModels.get(tenantCode);
            if (model != null) {
                return beanType.cast(model);
            }
        }
        return super.getPrimary(tenantCode, type, beanType);
    }

    private static String key(String tenantCode, String resourceKey) {
        return tenantCode + "|" + resourceKey;
    }
}

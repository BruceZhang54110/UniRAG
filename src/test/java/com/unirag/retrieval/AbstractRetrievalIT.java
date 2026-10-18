package com.unirag.retrieval;

import com.unirag.ingestion.AbstractIngestionIT;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceNotFoundException;
import com.unirag.upload.support.TenantContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索侧集成测试基类：在摄取基座之上补充 Web 层（MockMvc）、请求作用域下的租户上下文，
 * 以及问答所需的**替身聊天模型**装配。
 *
 * <p>检索服务经既有 {@code TenantContext} 定位租户（禁止自行解析 header），故服务层用例
 * 需模拟一次请求作用域；HTTP 用例直接走 MockMvc 由既有拦截器填充。
 */
@AutoConfigureMockMvc
public abstract class AbstractRetrievalIT extends AbstractIngestionIT {

    @Autowired
    protected MockMvc mockMvc;

    /** 在模拟请求作用域内执行用例，期间既有 {@link TenantContext} 已按租户码填充。 */
    protected <T> T inTenant(String tenantCode, Supplier<T> action) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            applicationContext.getBean(TenantContext.class).initialize(tenantCode, tenantId(tenantCode));
            return action.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    protected long tenantId(String tenantCode) {
        return jdbcTemplate.queryForObject("SELECT id FROM tenant WHERE tenant_code = ?", Long.class, tenantCode);
    }

    protected long pipelineId(String tenantCode, String pipelineKey) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ingestion_pipeline WHERE tenant_id = ? AND pipeline_key = ?",
                Long.class, tenantId(tenantCode), pipelineKey);
    }

    /** 观测表与摄取三表互不相关，需按租户单独清理，保证链路与条数断言确定。 */
    protected void cleanupObservations(String tenantCode) {
        jdbcTemplate.update(
                "DELETE FROM retrieval_observation WHERE tenant_id = (SELECT id FROM tenant WHERE tenant_code = ?)",
                tenantCode);
    }

    /** 装配替身聊天模型（不访问外网、不使用任何真实凭据，宪法原则 III）。 */
    protected OpenAiChatModel stubChatModel(String tenantCode) {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(tenantResourceRouter.getPrimary(eq(tenantCode), eq(ResourceType.LLM), eq(OpenAiChatModel.class)))
                .thenReturn(model);
        return model;
    }

    /** 让租户 LLM 解析失败（未配置/配置失效），断言 LLM_UNAVAILABLE 语义。 */
    protected void stubMissingChatModel(String tenantCode) {
        when(tenantResourceRouter.getPrimary(eq(tenantCode), eq(ResourceType.LLM), eq(OpenAiChatModel.class)))
                .thenThrow(TenantResourceNotFoundException.noPrimary(tenantCode, ResourceType.LLM, 0));
    }

    /** 让租户嵌入模型解析失败（未配置/配置失效），断言 EMBEDDING_MODEL_UNAVAILABLE 语义。 */
    protected void stubMissingEmbeddingModel(String tenantCode, String modelKey) {
        when(tenantResourceRouter.get(eq(tenantCode), eq(ResourceType.EMBEDDING_MODEL), eq(modelKey),
                eq(EmbeddingModel.class)))
                .thenThrow(TenantResourceNotFoundException.notFound(tenantCode, ResourceType.EMBEDDING_MODEL, modelKey));
    }

    protected ChatResponse chatResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}

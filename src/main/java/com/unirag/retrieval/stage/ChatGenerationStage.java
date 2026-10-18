package com.unirag.retrieval.stage;

import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import com.unirag.upload.support.ApiException;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 聊天模型调用（FR-004/FR-005，research.md R8）：经 Router 取该租户 LLM 生成回答。
 *
 * <p>1.20.0 已移除 {@code ChatLanguageModel}/{@code generate}，统一走
 * {@code ChatModel#chat(ChatRequest)}，token 上限字段名为 {@code maxOutputTokens}。
 * 检索为空时**不调用模型**，直接返回"未找到相关内容"的降级结果；模型不可达与返回空内容
 * 收敛为 {@code LLM_UNAVAILABLE} / {@code LLM_EMPTY_RESPONSE}，失败原因经脱敏后给出（原则 V）。
 */
@Component
public class ChatGenerationStage implements Stage<PromptBundle, QaAnswer> {

    public static final String SYSTEM_PROMPT = """
            你是企业知识库问答助手。只能依据用户消息中给出的资料片段作答，不得引入资料之外的信息。
            引用资料时用 [序号] 标注所依据的片段；资料不足以回答时明确说明未找到相关内容。""";

    public static final double TEMPERATURE = 0.2;

    public static final int MAX_OUTPUT_TOKENS = 1024;

    private final TenantResourceRouter router;

    public ChatGenerationStage(TenantResourceRouter router) {
        this.router = router;
    }

    @Override
    public StageName name() {
        return StageName.CHAT_GENERATION;
    }

    @Override
    public QaAnswer execute(PromptBundle prompt, StageContext ctx) {
        if (!prompt.hasContext()) {
            return QaAnswer.noContext();
        }
        OpenAiChatModel model = resolveChatModel(ctx.tenantCode());
        ChatResponse response;
        try {
            response = model.chat(ChatRequest.builder()
                    .messages(List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(prompt.prompt())))
                    .temperature(TEMPERATURE)
                    .maxOutputTokens(MAX_OUTPUT_TOKENS)
                    .build());
        } catch (RuntimeException e) {
            throw new ApiException("LLM_UNAVAILABLE", "聊天模型调用失败：" + Redaction.describe(e));
        }
        String answer = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
        if (answer == null || answer.isBlank()) {
            throw new ApiException("LLM_EMPTY_RESPONSE", "聊天模型返回空内容");
        }
        return new QaAnswer(answer, prompt.citations(), false, null);
    }

    /** 无 LLM 资源（未配置或配置失效）与调用失败同属"模型不可达"，统一给可读失败原因。 */
    private OpenAiChatModel resolveChatModel(String tenantCode) {
        try {
            return router.getPrimary(tenantCode, ResourceType.LLM, OpenAiChatModel.class);
        } catch (RuntimeException e) {
            throw new ApiException("LLM_UNAVAILABLE", "租户聊天模型不可用：" + Redaction.describe(e));
        }
    }
}

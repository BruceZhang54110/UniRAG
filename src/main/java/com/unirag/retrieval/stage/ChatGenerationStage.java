package com.unirag.retrieval.stage;

import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import com.unirag.common.web.ApiException;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * 聊天模型调用（FR-004/FR-005，research.md R8）：经 Router 取该租户 LLM 生成回答。
 *
 * <p>1.20.0 已移除 {@code ChatLanguageModel}/{@code generate}，统一走
 * {@code ChatModel#chat(ChatRequest)}，token 上限字段名为 {@code maxOutputTokens}。
 * 检索为空时**不调用模型**，直接返回"未找到相关内容"的降级结果；模型不可达与返回空内容
 * 收敛为 {@code LLM_UNAVAILABLE} / {@code LLM_EMPTY_RESPONSE}，失败原因经脱敏后给出（原则 V）。
 * 回答生成后按其中的 {@code [n]} 标注收窄引用清单，只返回实际引用到的片段。
 */
@Slf4j
@Component
public class ChatGenerationStage implements Stage<PromptBundle, QaAnswer> {

    public static final String SYSTEM_PROMPT = """
            你是企业知识库问答助手。只能依据用户消息中给出的资料片段作答，不得引入资料之外的信息。
            引用资料时用 [序号] 标注所依据的片段；资料不足以回答时明确说明未找到相关内容。""";

    public static final double TEMPERATURE = 0.2;

    public static final int MAX_OUTPUT_TOKENS = 1024;

    /** 回答中的引用标注，如 {@code [1]}；容忍方括号内的空白。 */
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[\\s*(\\d+)\\s*]");

    /** 日志里回答文本的展示上限，避免长回答刷屏。 */
    private static final int LOG_ANSWER_PREVIEW = 200;

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
        List<RetrievedChunk> citations = citationsCitedBy(answer, prompt.citations());
        if (log.isDebugEnabled()) {
            log.debug("[retrieval] trace={} CHAT_GENERATION answerChars={} candidates={} cited={} answer={}",
                    ctx.traceId(), answer.length(), prompt.citations().size(), citations.size(), preview(answer));
        }
        return new QaAnswer(answer, citations, false, null);
    }

    /**
     * 只保留回答中以 {@code [n]} 标注引用的片段，保持提示词中的相对顺序。
     * 回答没有任何有效标注时保留全部候选，避免丢失来源清单。
     */
    static List<RetrievedChunk> citationsCitedBy(String answer, List<RetrievedChunk> citations) {
        Set<Integer> cited = new HashSet<>();
        Matcher matcher = CITATION_MARKER.matcher(answer);
        while (matcher.find()) {
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException tooLarge) {
                continue;
            }
            if (index >= 1 && index <= citations.size()) {
                cited.add(index - 1);
            }
        }
        if (cited.isEmpty()) {
            return citations;
        }
        return IntStream.range(0, citations.size())
                .filter(cited::contains)
                .mapToObj(citations::get)
                .toList();
    }

    /** 归一空白并截断，日志可读且不会因长回答刷屏。 */
    private static String preview(String text) {
        String normalized = text.strip().replaceAll("\\s+", " ");
        return normalized.length() <= LOG_ANSWER_PREVIEW
                ? normalized
                : normalized.substring(0, LOG_ANSWER_PREVIEW) + "…";
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

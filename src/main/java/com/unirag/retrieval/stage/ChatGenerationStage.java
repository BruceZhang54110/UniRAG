package com.unirag.retrieval.stage;

import com.unirag.retrieval.Redaction;
import com.unirag.retrieval.memory.ChatMemoryFactory;
import com.unirag.retrieval.memory.ChatMemoryOutcome;
import com.unirag.retrieval.memory.ChatMemoryProperties;
import com.unirag.retrieval.model.QaAnswer;
import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import com.unirag.tenant.resource.ResourceType;
import com.unirag.tenant.resource.TenantResourceRouter;
import com.unirag.common.web.ApiException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    private final ChatMemoryFactory chatMemoryFactory;
    private final ChatMemoryProperties chatMemoryProperties;

    public ChatGenerationStage(TenantResourceRouter router, ChatMemoryFactory chatMemoryFactory,
                               ChatMemoryProperties chatMemoryProperties) {
        this.router = router;
        this.chatMemoryFactory = chatMemoryFactory;
        this.chatMemoryProperties = chatMemoryProperties;
    }

    @Override
    public StageName name() {
        return StageName.CHAT_GENERATION;
    }

    @Override
    public QaAnswer execute(PromptBundle prompt, StageContext ctx) {
        Optional<ChatMemory> memory = memoryOf(ctx);
        if (!prompt.hasContext()) {
            QaAnswer noContext = QaAnswer.noContext();
            // FR-006：降级轮是否入记忆可配置（默认写入）；问题取 queryDigest（截断脱敏版）
            if (chatMemoryProperties.recordNoContextRound() && ctx.queryDigest() != null) {
                remember(memory, ctx, AiMessage.from(noContext.answer()));
            }
            return noContext;
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        memory.ifPresent(chatMemory -> messages.addAll(historyOf(chatMemory, ctx)));
        messages.add(UserMessage.from(prompt.prompt()));
        return generate(prompt, ctx, memory, messages);
    }

    /** 流式问答：sink 存在时优先流式模型逐段推送；未配置流式模型则同步生成后整段缓冲推送（R3）。 */
    private QaAnswer generate(PromptBundle prompt, StageContext ctx, Optional<ChatMemory> memory,
                              List<ChatMessage> messages) {
        ChatTokenSink sink = ctx.get(ChatTokenSink.CONTEXT_KEY, ChatTokenSink.class);
        if (sink != null) {
            Optional<OpenAiStreamingChatModel> streaming = streamingModelOf(ctx.tenantCode());
            if (streaming.isPresent()) {
                return generateStreaming(prompt, ctx, memory, messages, streaming.get(), sink);
            }
        }
        QaAnswer answer = generateSync(prompt, ctx, memory, messages);
        if (sink != null && answer.answer() != null && !sink.cancelled().get()) {
            sink.emit(answer.answer());   // 缓冲降级：协议不变，单条 message（research.md R3）
        }
        return answer;
    }

    /** 同步生成（sink 不存在或流式模型缺失时的唯一路径，与 015 行为一致）。 */
    private QaAnswer generateSync(PromptBundle prompt, StageContext ctx, Optional<ChatMemory> memory,
                                  List<ChatMessage> messages) {
        OpenAiChatModel model = resolveChatModel(ctx.tenantCode());
        ChatResponse response;
        try {
            response = model.chat(ChatRequest.builder()
                    .messages(messages)
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
        return finish(prompt, ctx, memory, answer, response.aiMessage(), false, null);
    }

    /**
     * 流式生成（016-sse-chat-streaming）：handler 回调在模型线程上异步到达，流水线线程
     * 阻塞至完成；客户端中止（{@code sink.cancelled}）时停止推送并**跳过记忆写回**，
     * 返回降级结果（degradeReason=CLIENT_ABORTED，FR-008/R9）。
     */
    private QaAnswer generateStreaming(PromptBundle prompt, StageContext ctx, Optional<ChatMemory> memory,
                                       List<ChatMessage> messages, OpenAiStreamingChatModel model,
                                       ChatTokenSink sink) {
        StringBuilder answer = new StringBuilder();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        try {
            model.chat(ChatRequest.builder()
                    .messages(messages)
                    .temperature(TEMPERATURE)
                    .maxOutputTokens(MAX_OUTPUT_TOKENS)
                    .build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    answer.append(partialResponse);
                    sink.emit(partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    completed.set(response);
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    failed.set(error);
                    latch.countDown();
                }
            });
        } catch (RuntimeException e) {
            throw new ApiException("LLM_UNAVAILABLE", "聊天模型调用失败：" + Redaction.describe(e));
        }
        awaitCompletion(latch, ctx);
        if (failed.get() != null) {
            throw new ApiException("LLM_UNAVAILABLE", "聊天模型调用失败：" + Redaction.describe(failed.get()));
        }
        String full = answer.toString();
        if (full.isBlank()) {
            throw new ApiException("LLM_EMPTY_RESPONSE", "聊天模型返回空内容");
        }
        if (sink.cancelled().get()) {
            // 客户端中止：该轮视为未完成，不入记忆（FR-008）
            return finish(prompt, ctx, memory, full, AiMessage.from(full), true, "CLIENT_ABORTED");
        }
        ChatMessage aiMessage = completed.get() != null && completed.get().aiMessage() != null
                ? completed.get().aiMessage()
                : AiMessage.from(full);
        return finish(prompt, ctx, memory, full, aiMessage, false, null);
    }

    private static void awaitCompletion(CountDownLatch latch, StageContext ctx) {
        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                throw new ApiException("LLM_UNAVAILABLE", "聊天模型流式响应超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("LLM_UNAVAILABLE", "聊天模型流式响应被中断");
        }
    }

    /** 收尾：引用收窄 + 观测日志 + 记忆写回（degraded 时跳过）。 */
    private QaAnswer finish(PromptBundle prompt, StageContext ctx, Optional<ChatMemory> memory,
                            String answer, ChatMessage aiMessage, boolean degraded, String degradeReason) {
        List<RetrievedChunk> citations = citationsCitedBy(answer, prompt.citations());
        if (log.isDebugEnabled()) {
            log.debug("[retrieval] trace={} CHAT_GENERATION answerChars={} candidates={} cited={} degraded={} answer={}",
                    ctx.traceId(), answer.length(), prompt.citations().size(), citations.size(),
                    degraded, preview(answer));
        }
        if (!degraded) {
            remember(memory, ctx, aiMessage);
        }
        return new QaAnswer(answer, citations, degraded, degradeReason);
    }

    /** 流式模型解析；租户未配置（或未启用流式）时返回空，走缓冲降级（R3）。 */
    private Optional<OpenAiStreamingChatModel> streamingModelOf(String tenantCode) {
        try {
            return Optional.of(router.getPrimary(tenantCode, ResourceType.STREAMING_LLM,
                    OpenAiStreamingChatModel.class));
        } catch (RuntimeException e) {
            log.debug("[chat-stream] 流式模型不可用，走缓冲降级: tenant={}, type={}",
                    tenantCode, e.getClass().getName());
            return Optional.empty();
        }
    }

    /** 记忆参与：无 sessionId / 开关关闭时为空 Optional（FR-001）。 */
    private Optional<ChatMemory> memoryOf(StageContext ctx) {
        try {
            return chatMemoryFactory.get(ctx.tenantId(), ctx.sessionId());
        } catch (RuntimeException e) {
            log.warn("[chat-memory] 记忆实例获取失败，按无记忆处理: trace={}, type={}",
                    ctx.traceId(), e.getClass().getName());
            return Optional.empty();
        }
    }

    /** 读取记忆窗口；读取失败按无记忆处理（FR-007），降级原因经 remember 一并记录。 */
    private List<ChatMessage> historyOf(ChatMemory chatMemory, StageContext ctx) {
        try {
            List<ChatMessage> history = chatMemory.messages();
            return history == null ? List.of() : history;
        } catch (RuntimeException e) {
            log.warn("[chat-memory] 记忆读取失败，按无历史处理: trace={}, type={}",
                    ctx.traceId(), e.getClass().getName());
            return List.of();
        }
    }

    /**
     * 写回本轮问答（一问一答成对，逐出后不留孤儿消息）；全部失败 fail-open，
     * 统计 outcome 写上下文供观测扩展落库（FR-007/FR-008）。消息原文 MUST NOT 进日志。
     *
     * <p>记忆存用户问题原文（{@code queryDigest}，即请求问题），不存拼装了资料片段的提示词——
     * 避免旧资料反复注入后续轮次（spec 015 Key Entities：仅问答原文）。
     */
    private void remember(Optional<ChatMemory> memory, StageContext ctx, ChatMessage aiMessage) {
        if (memory.isEmpty() || ctx.queryDigest() == null) {
            return;
        }
        ChatMessage userMessage = UserMessage.from(ctx.queryDigest());
        ChatMemory chatMemory = memory.get();
        try {
            int before = chatMemory.messages().size();
            chatMemory.add(userMessage);
            chatMemory.add(aiMessage);
            int after = chatMemory.messages().size();
            int evicted = Math.max(0, before + 2 - after);
            ctx.put(ChatMemoryOutcome.CONTEXT_KEY,
                    new ChatMemoryOutcome(after, evicted, false, null));
        } catch (RuntimeException e) {
            log.warn("[chat-memory] 记忆写回失败，问答按无记忆完成: trace={}, type={}",
                    ctx.traceId(), e.getClass().getName());
            ctx.put(ChatMemoryOutcome.CONTEXT_KEY,
                    new ChatMemoryOutcome(0, 0, true, e.getClass().getSimpleName()));
        }
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

package com.unirag.ingestion;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.springframework.stereotype.Component;

/**
 * 摄取 token 计量封装（research.md R3）：基于 {@link OpenAiTokenCountEstimator}（tiktoken）。
 *
 * <p>注意：tiktoken 对中文为子词近似计量，可接受但非精确；如需中文专精计量，
 * 以本类为替换点实现 {@link TokenCountEstimator} 即可，切片与 token_count 统计共用一处。
 */
@Component
public class IngestionTokenizer implements TokenCountEstimator {

    /** 默认计量模型（O200K_BASE 编码，对中文子词近似较好）。 */
    public static final String DEFAULT_MODEL_NAME = "gpt-4o";

    private final OpenAiTokenCountEstimator delegate;

    public IngestionTokenizer() {
        this(DEFAULT_MODEL_NAME);
    }

    public IngestionTokenizer(String modelName) {
        this.delegate = new OpenAiTokenCountEstimator(modelName);
    }

    /**
     * 估算文本 token 数（用于切片上限与 document_chunk.token_count 计量）。
     */
    public int estimateTokens(String text) {
        return delegate.estimateTokenCountInText(text);
    }

    @Override
    public int estimateTokenCountInText(String text) {
        return estimateTokens(text);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        return delegate.estimateTokenCountInMessage(message);
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        return delegate.estimateTokenCountInMessages(messages);
    }
}

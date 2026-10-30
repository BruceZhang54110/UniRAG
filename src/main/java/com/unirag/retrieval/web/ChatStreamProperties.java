package com.unirag.retrieval.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 流式问答配置（specs/016-sse-chat-streaming/data-model.md §3）。
 *
 * @param emitterTimeout SSE 连接超时；超时按 error 事件收尾，不留悬挂连接（SC-005）
 */
@ConfigurationProperties(prefix = "unirag.chat-stream")
public record ChatStreamProperties(@DefaultValue("120s") Duration emitterTimeout) {

    public ChatStreamProperties {
        if (emitterTimeout.isNegative() || emitterTimeout.isZero()) {
            throw new IllegalArgumentException("unirag.chat-stream.emitter-timeout 必须为正数");
        }
    }
}

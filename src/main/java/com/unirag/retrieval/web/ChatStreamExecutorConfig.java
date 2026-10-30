package com.unirag.retrieval.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 流式问答异步执行器（research.md R5）：问答链是阻塞调用，SSE 不能在容器请求线程上执行。
 *
 * <p>池子小而专用（与调度/杂务隔离，故障互不拖累）；有界队列 + AbortPolicy：
 * 流式连接瞬时洪峰时快速失败走 error 事件，而不是无限排队拖垮全部问答。
 */
@Configuration
public class ChatStreamExecutorConfig {

    @Bean(name = "chatStreamExecutor")
    public Executor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("chat-stream-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}

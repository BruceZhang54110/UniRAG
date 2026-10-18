package com.unirag.retrieval.stage;

import com.unirag.retrieval.model.RetrievedChunk;

import java.util.List;

/**
 * 提示词组装阶段的输出。
 *
 * @param prompt    带引用标注的提示词；检索为空时为 {@code null}
 * @param citations 与提示词中引用标注同序的检索片段；生成回答后由
 *                  {@link ChatGenerationStage} 收窄为实际引用到的片段
 */
public record PromptBundle(String prompt, List<RetrievedChunk> citations) {

    public boolean hasContext() {
        return !citations.isEmpty();
    }
}

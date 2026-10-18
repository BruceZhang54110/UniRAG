package com.unirag.retrieval.stage;

import com.unirag.retrieval.model.RetrievedChunk;
import com.unirag.retrieval.pipeline.Stage;
import com.unirag.retrieval.pipeline.StageContext;
import com.unirag.retrieval.pipeline.StageName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 提示词组装（FR-004，contracts/retrieval-api.md §3）：把归并后的片段拼成带引用标注的提示词。
 *
 * <p>片段顺序即标注顺序即 {@code citations} 顺序 —— 引用清单与提示词中用户看到的编号一一对应，
 * 因此这里不重排、不去重。检索为空时返回空上下文，由后续阶段走"无知识"降级。
 */
@Slf4j
@Component
public class PromptAssemblyStage implements Stage<MergeResult, PromptBundle> {

    @Override
    public StageName name() {
        return StageName.PROMPT_ASSEMBLY;
    }

    @Override
    public PromptBundle execute(MergeResult merged, StageContext ctx) {
        List<RetrievedChunk> citations = merged.chunks();
        if (citations.isEmpty()) {
            log.debug("[retrieval] trace={} PROMPT_ASSEMBLY no context", ctx.traceId());
            return new PromptBundle(null, List.of());
        }
        StringBuilder prompt = new StringBuilder("问题：")
                .append(merged.request().query())
                .append("\n\n资料片段：\n");
        for (int i = 0; i < citations.size(); i++) {
            RetrievedChunk chunk = citations.get(i);
            prompt.append('[').append(i + 1).append("] 来源：docName=").append(chunk.docName())
                    .append("，pipelineKey=").append(chunk.pipelineKey())
                    .append("，chunkIndex=").append(chunk.chunkIndex()).append('\n')
                    .append(chunk.text()).append("\n\n");
        }
        log.debug("[retrieval] trace={} PROMPT_ASSEMBLY citations={} promptChars={}",
                ctx.traceId(), citations.size(), prompt.length());
        return new PromptBundle(prompt.toString(), List.copyOf(citations));
    }
}

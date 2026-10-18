package com.unirag.retrieval.stage;

import com.unirag.retrieval.model.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatGenerationStageTest {

    private static final List<RetrievedChunk> CHUNKS = List.of(
            chunk(0), chunk(1), chunk(2), chunk(3));

    @Test
    void 只保留回答中标注的片段且保持提示词顺序() {
        assertThat(ChatGenerationStage.citationsCitedBy("见 [3] 与 [1]", CHUNKS))
                .extracting(RetrievedChunk::chunkIndex)
                .containsExactly(0, 2);
    }

    @Test
    void 重复标注只保留一条() {
        assertThat(ChatGenerationStage.citationsCitedBy("[1][1]", CHUNKS))
                .extracting(RetrievedChunk::chunkIndex)
                .containsExactly(0);
    }

    @Test
    void 容忍方括号内的空白() {
        assertThat(ChatGenerationStage.citationsCitedBy("见 [ 2 ]", CHUNKS))
                .extracting(RetrievedChunk::chunkIndex)
                .containsExactly(1);
    }

    @Test
    void 有效标注与越界标注混用时只保留有效部分() {
        assertThat(ChatGenerationStage.citationsCitedBy("[9][2]", CHUNKS))
                .extracting(RetrievedChunk::chunkIndex)
                .containsExactly(1);
    }

    @Test
    void 零与越界标注不构成有效引用时回退为全部候选() {
        assertThat(ChatGenerationStage.citationsCitedBy("[0][9]", CHUNKS))
                .containsExactlyElementsOf(CHUNKS);
    }

    @Test
    void 回答没有标注时保留全部候选() {
        assertThat(ChatGenerationStage.citationsCitedBy("没有标注的回答", CHUNKS))
                .containsExactlyElementsOf(CHUNKS);
    }

    @Test
    void 超大序号不会中断过滤() {
        assertThat(ChatGenerationStage.citationsCitedBy("[" + "9".repeat(20) + "][2]", CHUNKS))
                .extracting(RetrievedChunk::chunkIndex)
                .containsExactly(1);
    }

    private static RetrievedChunk chunk(int chunkIndex) {
        return new RetrievedChunk(1L, "doc", "main", null, chunkIndex, 0.5, "text-" + chunkIndex);
    }
}

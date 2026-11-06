package com.unirag.retrieval.stage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章节过滤键组装单测（tasks.md T014）：section 值层级 → section_N 键映射。
 */
class VectorSearchStageSectionFilterTest {

    @Test
    void chapterValueMapsToLevelOneKey() {
        assertThat(VectorSearchStage.sectionKey("二、重点任务")).isEqualTo("section_1");
    }

    @Test
    void subsectionValueMapsToLevelTwoKey() {
        assertThat(VectorSearchStage.sectionKey("二、重点任务/（三）生态协同建设")).isEqualTo("section_2");
    }

    @Test
    void deepValuesCappedAtSixLevels() {
        String six = "a/b/c/d/e/f";
        String seven = "a/b/c/d/e/f/g";
        assertThat(VectorSearchStage.sectionKey(six)).isEqualTo("section_6");
        assertThat(VectorSearchStage.sectionKey(seven)).isEqualTo("section_6");
    }
}

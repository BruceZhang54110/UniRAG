package com.unirag.eval.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.eval.metric.Hashing;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void replaysEmbeddingByExactText() throws Exception {
        Path fixture = embeddingFixture(1, 2, Map.of(
                Hashing.sha256Hex("hello"), new float[]{1.0f, 0.0f}));
        ReplayEmbeddingModel model = ReplayEmbeddingModel.load(fixture, mapper);

        assertArrayEquals(new float[]{1.0f, 0.0f}, model.embed("hello").content().vector(), 0.0001f);
        assertEquals(1, model.embedAll(List.of(TextSegment.from("hello"))).content().size());
    }

    @Test
    void rejectsMissingEmbeddingVector() throws Exception {
        Path fixture = embeddingFixture(1, 2, Map.of(
                Hashing.sha256Hex("hello"), new float[]{1.0f, 0.0f}));
        ReplayEmbeddingModel model = ReplayEmbeddingModel.load(fixture, mapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> model.embed("missing"));
        assertTrue(error.getMessage().contains("missing vector"), error.getMessage());
    }

    @Test
    void rejectsEmbeddingDimensionMismatch() throws Exception {
        Path fixture = embeddingFixture(1, 2, Map.of(
                Hashing.sha256Hex("hello"), new float[]{1.0f, 0.0f, 0.5f}));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ReplayEmbeddingModel.load(fixture, mapper));
        assertTrue(error.getMessage().contains("维度不一致"), error.getMessage());
    }

    @Test
    void rejectsUnsupportedEmbeddingFixtureVersion() throws Exception {
        Path fixture = embeddingFixture(2, 2, Map.of(
                Hashing.sha256Hex("hello"), new float[]{1.0f, 0.0f}));

        assertThrows(IllegalStateException.class, () -> ReplayEmbeddingModel.load(fixture, mapper));
    }

    @Test
    void rejectsMissingEmbeddingFixtureFile() {
        assertThrows(IllegalStateException.class,
                () -> ReplayEmbeddingModel.load(tempDir.resolve("absent.json"), mapper));
    }

    @Test
    void replaysRerankScoreByQueryAndText() throws Exception {
        String key = RecordingScoringModel.key("query", "text");
        Path fixture = rerankFixture(1, Map.of(key, 0.75));
        ReplayScoringModel model = ReplayScoringModel.load(fixture, mapper);

        assertEquals(0.75, model.score(TextSegment.from("text"), "query").content(), 0.0001);
    }

    @Test
    void rejectsMissingRerankScore() throws Exception {
        Path fixture = rerankFixture(1, Map.of(RecordingScoringModel.key("query", "text"), 0.75));
        ReplayScoringModel model = ReplayScoringModel.load(fixture, mapper);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> model.score(TextSegment.from("missing"), "query"));
        assertTrue(error.getMessage().contains("missing score"), error.getMessage());
    }

    @Test
    void rejectsUnsupportedRerankFixtureVersion() throws Exception {
        Path fixture = rerankFixture(2, Map.of());

        assertThrows(IllegalStateException.class, () -> ReplayScoringModel.load(fixture, mapper));
    }

    @Test
    void rejectsMissingRerankFixtureFile() {
        assertThrows(IllegalStateException.class,
                () -> ReplayScoringModel.load(tempDir.resolve("absent-rerank.json"), mapper));
    }

    private Path embeddingFixture(int version, int dimension, Map<String, float[]> vectors) throws Exception {
        Path path = tempDir.resolve("embeddings-" + version + "-" + dimension + ".json");
        Files.write(path, mapper.writeValueAsBytes(
                new ReplayEmbeddingModel.Fixture(version, "test-model", dimension, vectors)));
        return path;
    }

    private Path rerankFixture(int version, Map<String, Double> scores) throws Exception {
        Path path = tempDir.resolve("rerank-" + version + ".json");
        Files.write(path, mapper.writeValueAsBytes(
                new ReplayScoringModel.Fixture(version, "test-reranker", scores)));
        return path;
    }
}

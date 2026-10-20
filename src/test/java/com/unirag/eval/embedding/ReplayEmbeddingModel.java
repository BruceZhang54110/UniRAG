package com.unirag.eval.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.eval.metric.Hashing;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** replay 模式：按精确文本 SHA-256 查冻结向量（013 contracts/replay-fixture.md）。 */
public final class ReplayEmbeddingModel implements EmbeddingModel {

    private final Map<String, float[]> vectors;

    private final int dimension;

    private final String modelName;

    public ReplayEmbeddingModel(Map<String, float[]> vectors, int dimension, String modelName) {
        this.vectors = Map.copyOf(vectors);
        this.dimension = dimension;
        this.modelName = modelName;
    }

    public static ReplayEmbeddingModel load(Path fixturePath, ObjectMapper mapper) throws IOException {
        if (!Files.exists(fixturePath)) {
            throw new IllegalStateException("replay 夹具不存在: " + fixturePath.toAbsolutePath());
        }
        Fixture fixture = mapper.readValue(Files.readAllBytes(fixturePath), Fixture.class);
        if (fixture.fixtureVersion() != 1) {
            throw new IllegalStateException("不支持的夹具版本: " + fixture.fixtureVersion());
        }
        if (fixture.dimension() <= 0) {
            throw new IllegalStateException("夹具维度非法: " + fixture.dimension());
        }
        fixture.vectors().forEach((key, vector) -> {
            if (vector.length != fixture.dimension()) {
                throw new IllegalStateException("夹具向量维度不一致: key=" + key
                        + ", expected=" + fixture.dimension() + ", actual=" + vector.length);
            }
        });
        return new ReplayEmbeddingModel(fixture.vectors(), fixture.dimension(), fixture.model());
    }

    @Override
    public Response<Embedding> embed(String text) {
        return Response.from(Embedding.from(lookup(text)));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = new ArrayList<>(textSegments.size());
        for (TextSegment segment : textSegments) {
            embeddings.add(Embedding.from(lookup(segment.text())));
        }
        return Response.from(embeddings);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private float[] lookup(String text) {
        String key = Hashing.sha256Hex(text);
        float[] vector = vectors.get(key);
        if (vector == null) {
            throw new IllegalStateException("Replay fixture missing vector: key=" + key
                    + ", textPreview=\"" + preview(text) + "\"");
        }
        return vector;
    }

    private static String preview(String text) {
        String normalized = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "…";
    }

    public record Fixture(int fixtureVersion, String model, int dimension, Map<String, float[]> vectors) {

        public Fixture {
            vectors = vectors == null ? Map.of() : Map.copyOf(vectors);
        }
    }
}

package com.unirag.eval.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.unirag.eval.metric.Hashing;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** record 模式：调用真实模型，同时按精确文本记录向量（013 contracts/replay-fixture.md §4）。 */
public final class RecordingEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;

    private final Map<String, float[]> recorded = new LinkedHashMap<>();

    public RecordingEmbeddingModel(EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response<Embedding> embed(String text) {
        Response<Embedding> response = delegate.embed(text);
        record(text, response.content().vector());
        return response;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        Response<List<Embedding>> response = delegate.embedAll(textSegments);
        List<Embedding> embeddings = response.content();
        for (int i = 0; i < textSegments.size(); i++) {
            record(textSegments.get(i).text(), embeddings.get(i).vector());
        }
        return response;
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    public Map<String, float[]> snapshot() {
        return Map.copyOf(recorded);
    }

    public void writeFixture(Path fixturePath, String modelName) throws IOException {
        Map<String, float[]> sorted = new TreeMap<>(recorded);
        ReplayEmbeddingModel.Fixture fixture = new ReplayEmbeddingModel.Fixture(
                1, modelName, delegate.dimension(), sorted);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        byte[] bytes = mapper.writeValueAsBytes(fixture);

        Files.createDirectories(fixturePath.getParent());
        Path temp = fixturePath.resolveSibling(fixturePath.getFileName() + ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, fixturePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void record(String text, float[] vector) {
        recorded.put(Hashing.sha256Hex(text), vector.clone());
    }
}

package com.unirag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.unirag.eval.dataset.QueryCase;
import com.unirag.eval.embedding.ReplayEmbeddingModel;
import com.unirag.eval.metric.Hashing;
import com.unirag.ingestion.DocumentSplitterFactory;
import com.unirag.ingestion.IngestionTokenizer;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成 sample 数据集的测试用冻结向量，用于验证 replay 路径。
 *
 * <p>注意：这是确定性的合成向量，只用于 harness 自测，不代表真实语义基线。
 */
class SampleReplayFixtureTest {

    private static final int DIMENSION = 8;

    @Test
    void generatesReplayFixtureForSampleDataset() throws Exception {
        Path datasetDir = Path.of("eval/datasets/sample");
        Map<String, float[]> vectors = new LinkedHashMap<>();

        DocumentSplitter splitter = DocumentSplitterFactory.create(
                "RECURSIVE", 64, 8, new IngestionTokenizer());
        try (Stream<Path> files = Files.walk(datasetDir.resolve("corpus"))) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                Document document = Document.from(Files.readString(file, StandardCharsets.UTF_8));
                for (TextSegment segment : splitter.split(document)) {
                    vectors.put(Hashing.sha256Hex(segment.text()), vector(segment.text()));
                }
            }
        }

        ObjectMapper mapper = new ObjectMapper();
        for (String line : Files.readAllLines(datasetDir.resolve("queries.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            QueryCase query = mapper.readValue(line, QueryCase.class);
            vectors.put(Hashing.sha256Hex(query.question()), vector(query.question()));
        }

        ReplayEmbeddingModel.Fixture fixture = new ReplayEmbeddingModel.Fixture(
                1, "deterministic-replay", DIMENSION, new TreeMap<>(vectors));
        Path fixturePath = Path.of("eval/fixtures/sample/embeddings.json");
        Files.createDirectories(fixturePath.getParent());
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        Files.write(fixturePath, mapper.writeValueAsBytes(fixture));

        assertTrue(Files.exists(fixturePath));
        assertTrue(vectors.size() >= 3);
    }

    private static float[] vector(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            float[] vector = new float[DIMENSION];
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] = ((digest[i] & 0xff) / 255.0f) - 0.5f;
            }
            return vector;
        } catch (Exception e) {
            throw new IllegalStateException("无法生成测试向量", e);
        }
    }
}

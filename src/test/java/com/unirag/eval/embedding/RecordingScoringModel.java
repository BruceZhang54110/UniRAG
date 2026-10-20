package com.unirag.eval.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.unirag.eval.metric.Hashing;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** record 模式：调用真实重排模型，同时按“查询 + 片段文本”记录分数。 */
public final class RecordingScoringModel implements ScoringModel {

    private final ScoringModel delegate;

    private final Map<String, Double> recorded = new LinkedHashMap<>();

    public RecordingScoringModel(ScoringModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public Response<Double> score(TextSegment segment, String query) {
        Response<Double> response = delegate.score(segment, query);
        recorded.put(key(query, segment.text()), response.content());
        return response;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        Response<List<Double>> response = delegate.scoreAll(segments, query);
        List<Double> scores = response.content();
        for (int i = 0; i < segments.size(); i++) {
            recorded.put(key(query, segments.get(i).text()), scores.get(i));
        }
        return response;
    }

    public void writeFixture(Path fixturePath, String modelName) throws IOException {
        ReplayScoringModel.Fixture fixture = new ReplayScoringModel.Fixture(
                1, modelName, new TreeMap<>(recorded));
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        byte[] bytes = mapper.writeValueAsBytes(fixture);

        Files.createDirectories(fixturePath.getParent());
        Path temp = fixturePath.resolveSibling(fixturePath.getFileName() + ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, fixturePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String key(String query, String text) {
        return Hashing.sha256Hex(query + "\u0000" + text);
    }

    static List<String> keys(String query, List<TextSegment> segments) {
        List<String> keys = new ArrayList<>(segments.size());
        segments.forEach(segment -> keys.add(key(query, segment.text())));
        return keys;
    }
}

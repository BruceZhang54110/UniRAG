package com.unirag.eval.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** replay 模式：按“查询 + 片段文本”SHA-256 查冻结重排分数。 */
public final class ReplayScoringModel implements ScoringModel {

    private final Map<String, Double> scores;

    private final String modelName;

    public ReplayScoringModel(Map<String, Double> scores, String modelName) {
        this.scores = Map.copyOf(scores);
        this.modelName = modelName;
    }

    public static ReplayScoringModel load(Path fixturePath, ObjectMapper mapper) throws IOException {
        if (!Files.exists(fixturePath)) {
            throw new IllegalStateException("重排 replay 夹具不存在: " + fixturePath.toAbsolutePath());
        }
        Fixture fixture = mapper.readValue(Files.readAllBytes(fixturePath), Fixture.class);
        if (fixture.fixtureVersion() != 1) {
            throw new IllegalStateException("不支持的重排夹具版本: " + fixture.fixtureVersion());
        }
        return new ReplayScoringModel(fixture.scores(), fixture.model());
    }

    @Override
    public Response<Double> score(TextSegment segment, String query) {
        return Response.from(lookup(RecordingScoringModel.key(query, segment.text()),
                segment.text()));
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        List<Double> result = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            result.add(lookup(RecordingScoringModel.key(query, segment.text()), segment.text()));
        }
        return Response.from(result);
    }

    private double lookup(String key, String text) {
        Double value = scores.get(key);
        if (value == null) {
            throw new IllegalStateException("Replay rerank fixture missing score: key=" + key
                    + ", textPreview=\"" + preview(text) + "\"");
        }
        return value;
    }

    private static String preview(String text) {
        String normalized = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "…";
    }

    public record Fixture(int fixtureVersion, String model, Map<String, Double> scores) {

        public Fixture {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
        }
    }
}

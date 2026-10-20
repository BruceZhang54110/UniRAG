package com.unirag.eval.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unirag.eval.metric.Hashing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 从 {@code eval/datasets/<name>/} 加载数据集（013 contracts/dataset.md）。 */
public final class DatasetLoader {

    private static final TypeReference<Map<String, ThresholdRule>> THRESHOLD_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper mapper;

    public DatasetLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EvaluationDataset load(Path datasetDir) {
        Path configPath = datasetDir.resolve("config.json");
        Path queriesPath = datasetDir.resolve("queries.jsonl");
        Path corpusDir = datasetDir.resolve("corpus");
        try {
            byte[] configBytes = Files.readAllBytes(configPath);
            byte[] queriesBytes = Files.readAllBytes(queriesPath);
            DatasetConfig config = mapper.readValue(configBytes, DatasetConfig.class);
            List<QueryCase> queries = readQueries(queriesPath);
            List<CorpusDocument> corpus = readCorpus(corpusDir);
            Map<String, ThresholdRule> thresholds = readThresholds(datasetDir.resolve("thresholds.json"));

            String corpusHash = Hashing.sha256Hex(canonicalCorpus(corpus));
            String queriesHash = Hashing.sha256Hex(queriesBytes);
            String configHash = Hashing.sha256Hex(configBytes);
            String version = Hashing.sha256Hex(corpusHash + "\n" + queriesHash + "\n" + configHash);
            return new EvaluationDataset(config.name(), corpus, queries, config, thresholds,
                    corpusHash, queriesHash, configHash, version);
        } catch (IOException e) {
            throw new DatasetValidationException("V-000",
                    "读取数据集失败: " + datasetDir.toAbsolutePath() + " (" + e.getMessage() + ")");
        }
    }

    private List<QueryCase> readQueries(Path queriesPath) throws IOException {
        List<QueryCase> queries = new ArrayList<>();
        int lineNo = 0;
        for (String line : Files.readAllLines(queriesPath, StandardCharsets.UTF_8)) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            try {
                queries.add(mapper.readValue(line, QueryCase.class));
            } catch (IOException e) {
                throw new DatasetValidationException("V-003",
                        "queries.jsonl 第 " + lineNo + " 行无法解析: " + e.getMessage());
            }
        }
        return queries;
    }

    private List<CorpusDocument> readCorpus(Path corpusDir) throws IOException {
        if (!Files.isDirectory(corpusDir)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(corpusDir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md")
                            || path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> corpusDir.relativize(path).toString()))
                    .toList();
        }
        List<CorpusDocument> documents = new ArrayList<>(files.size());
        for (Path file : files) {
            String docName = corpusDir.relativize(file).toString().replace('\\', '/');
            documents.add(new CorpusDocument(docName, docName,
                    Files.readString(file, StandardCharsets.UTF_8), docName));
        }
        return documents;
    }

    private Map<String, ThresholdRule> readThresholds(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Map.of();
        }
        return new LinkedHashMap<>(mapper.readValue(Files.readAllBytes(path), THRESHOLD_TYPE));
    }

    private static String canonicalCorpus(List<CorpusDocument> corpus) {
        StringBuilder builder = new StringBuilder();
        corpus.stream()
                .sorted(Comparator.comparing(CorpusDocument::docName))
                .forEach(doc -> builder.append(doc.docName()).append('\n')
                        .append(doc.content()).append('\n'));
        return builder.toString();
    }
}

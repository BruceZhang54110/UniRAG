package com.unirag.eval.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DatasetOnboardingTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsNewDatasetFromFilesWithoutCodeChanges() throws Exception {
        Path datasetDir = tempDir.resolve("new-dataset");
        Files.createDirectories(datasetDir.resolve("corpus"));
        Files.writeString(datasetDir.resolve("corpus/new.md"), "# 新文档\n\n新文档中的答案就在这一段。\n");
        Files.writeString(datasetDir.resolve("queries.jsonl"), """
                {"id":"n-001","question":"新文档的答案是什么？","tags":["fact"],"split":"dev","answerable":true,"relevant":[{"doc":"new.md","span":"新文档中的答案"}]}
                """);
        Files.writeString(datasetDir.resolve("config.json"), """
                {
                  "name": "new-dataset",
                  "tenant": "eval_new_dataset",
                  "store": {"table": "embeddings_eval_new_dataset", "dimension": 8},
                  "pipelines": [
                    {"key": "fine", "maxSegmentTokens": 64, "overlapTokens": 8,
                     "modelKey": "eval-embedding", "storeKey": "eval-store"}
                  ],
                  "retrieval": {"topK": 3, "recallTopK": 5, "minScore": 0.0,
                                "rerank": false, "rewrite": false},
                  "metrics": {"k": [1, 3], "diagnosticTopK": 10}
                }
                """);

        EvaluationDataset dataset = new DatasetLoader(new ObjectMapper()).load(datasetDir);
        assertDoesNotThrow(() -> new DatasetValidator().validate(dataset, "new-dataset"));
        assertEquals("new-dataset", dataset.name());
        assertEquals(1, dataset.corpus().size());
        assertEquals(1, dataset.queries().size());
        assertEquals("new.md", dataset.corpus().get(0).docName());
    }
}

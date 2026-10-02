package com.unirag.ingestion;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性嵌入模型测试替身（FR-009）：文本 → 固定维度向量的确定性映射，无外部模型凭据。
 *
 * <p>映射规则：逐字符滚动哈希到维度槽位累加（相同字符序列必得相同向量），再做 L2 归一化。
 * 维度由构造参数指定（1024/768 等），使双粒度不同维度用例可复用同一替身（tasks.md T002）。
 */
public class DeterministicEmbeddingModel implements EmbeddingModel {

    private final int dimension;
    private final String modelName;

    public DeterministicEmbeddingModel(int dimension) {
        this(dimension, "deterministic-embedding-" + dimension);
    }

    public DeterministicEmbeddingModel(int dimension, String modelName) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive: " + dimension);
        }
        this.dimension = dimension;
        this.modelName = modelName;
    }

    @Override
    public Response<Embedding> embed(String text) {
        return embed(TextSegment.from(text));
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        return Response.from(embedText(textSegment.text()));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<Embedding> embeddings = new ArrayList<>(textSegments.size());
        for (TextSegment segment : textSegments) {
            embeddings.add(embedText(segment.text()));
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

    private Embedding embedText(String text) {
        float[] vector = new float[dimension];
        int hash = 0;
        for (int i = 0; i < text.length(); i++) {
            // 滚动哈希打散字符，槽位累加保证确定性
            hash = 31 * hash + text.charAt(i);
            vector[Math.floorMod(hash, dimension)] += 1.0f;
        }
        if (text.isEmpty()) {
            vector[0] = 1.0f;
        }
        double norm = 0.0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0.0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return Embedding.from(vector);
    }
}

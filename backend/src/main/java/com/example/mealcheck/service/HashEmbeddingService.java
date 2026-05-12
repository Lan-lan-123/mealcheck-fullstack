package com.example.mealcheck.service;

import com.example.mealcheck.config.AppProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class HashEmbeddingService {
    private final int dimension;

    public HashEmbeddingService(AppProperties properties) {
        this.dimension = properties.getKnowledge().getEmbeddingDim();
    }

    public float[] embed(String text) {
        float[] vector = new float[dimension];
        if (text == null || text.isBlank()) {
            return vector;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}，。！？；：、（）【】《》“”‘’]", " ");
        String[] tokens = normalized.split("\\s+");
        for (String token : tokens) {
            if (!token.isBlank()) {
                addToken(vector, token, 1.0f);
            }
        }
        // Chinese text often has no whitespace; add character bigrams to improve recall.
        String noSpace = normalized.replaceAll("\\s+", "");
        for (int i = 0; i < noSpace.length() - 1; i++) {
            addToken(vector, noSpace.substring(i, i + 2), 0.35f);
        }
        normalize(vector);
        return vector;
    }

    public String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.6f", vector[i]));
        }
        return sb.append(']').toString();
    }

    private void addToken(float[] vector, String token, float weight) {
        int hash = murmurLike(token);
        int index = Math.floorMod(hash, vector.length);
        int sign = (hash & 1) == 0 ? 1 : -1;
        vector[index] += sign * weight;
    }

    private int murmurLike(String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        int h = 0x9747b28c;
        for (byte b : bytes) {
            h ^= b;
            h *= 0x5bd1e995;
            h ^= h >>> 15;
        }
        return h;
    }

    private void normalize(float[] vector) {
        double sum = 0.0;
        for (float v : vector) sum += v * v;
        double norm = Math.sqrt(sum);
        if (norm < 1e-9) return;
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }
}

package com.zhishiku.kb.common.embedding;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 供写入侧和查询侧共享的确定性离线 embedding 工具。
 */
public final class EmbeddingMath {

    private EmbeddingMath() {
    }

    public static float[] localHash(String text, int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("embedding dimension 必须为正数");
        }
        float[] vector = new float[dimension];
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int slot = Math.floorMod((bytes[i] & 0xff) * 31 + i * 17, dimension);
            vector[slot] += bytes[i] >= 0 ? 1f : -1f;
        }
        normalize(vector);
        return vector;
    }

    public static void normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= (float) norm;
        }
    }

    /**
     * pgvector 接受的字面量格式，同时拒绝会污染索引的非有限值。
     */
    public static String toPgVector(float[] vector) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            if (!Float.isFinite(vector[i])) {
                throw new IllegalArgumentException("embedding 含 NaN 或 Infinity");
            }
            result.append(String.format(Locale.ROOT, "%s", vector[i]));
        }
        return result.append(']').toString();
    }
}

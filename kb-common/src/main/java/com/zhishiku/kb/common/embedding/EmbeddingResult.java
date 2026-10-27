package com.zhishiku.kb.common.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class EmbeddingResult {

    private final EmbeddingProfile profile;
    private final EmbeddingRole role;
    private final List<float[]> vectors;

    public EmbeddingResult(EmbeddingProfile profile, EmbeddingRole role, List<float[]> vectors) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.role = Objects.requireNonNull(role, "role");
        Objects.requireNonNull(vectors, "vectors");
        List<float[]> copy = new ArrayList<>(vectors.size());
        for (float[] vector : vectors) {
            if (vector == null || vector.length != profile.dimension()) {
                throw new IllegalArgumentException("embedding dimension mismatch");
            }
            float[] snapshot = vector.clone();
            boolean nonzero = false;
            for (float value : snapshot) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("embedding contains non-finite values");
                }
                nonzero |= value != 0f;
            }
            if (!nonzero) {
                throw new IllegalArgumentException("embedding must not be a zero vector");
            }
            copy.add(snapshot);
        }
        this.vectors = List.copyOf(copy);
    }

    public EmbeddingProfile profile() {
        return profile;
    }

    public EmbeddingRole role() {
        return role;
    }

    public String profileId() {
        return profile.profileId();
    }

    public String fingerprint() {
        return profile.fingerprint();
    }

    public int size() {
        return vectors.size();
    }

    public List<float[]> vectors() {
        return vectors.stream().map(float[]::clone).toList();
    }

    public float[] vector(int index) {
        return vectors.get(index).clone();
    }

    public void require(EmbeddingProfile expected, EmbeddingRole expectedRole, int expectedSize) {
        if (!profile.compatibleWith(expected) || role != expectedRole || vectors.size() != expectedSize) {
            throw new IllegalArgumentException("embedding identity, role or count mismatch");
        }
    }

    @Override
    public String toString() {
        return "EmbeddingResult[profileId=" + profileId() + ", role=" + role + ", size=" + size() + "]";
    }
}

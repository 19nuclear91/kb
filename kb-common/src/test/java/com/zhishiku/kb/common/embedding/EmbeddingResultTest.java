package com.zhishiku.kb.common.embedding;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingResultTest {

    @Test
    void snapshotsArraysOnInputAndEveryOutput() {
        float[] vector = vector();
        List<float[]> input = new ArrayList<>(List.of(vector));
        EmbeddingResult result = new EmbeddingResult(EmbeddingProfileTest.profile(), EmbeddingRole.QUERY, input);
        vector[0] = 7f;
        input.clear();
        result.vector(0)[0] = 8f;
        result.vectors().getFirst()[0] = 9f;
        assertThat(result.vector(0)[0]).isEqualTo(1f);
        assertThat(result.size()).isEqualTo(1);
        assertThatThrownBy(() -> result.vectors().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.toString()).doesNotContain("1.0", "apiKey", "endpoint");
    }

    @Test
    void verifiesIdentityRoleAndCountTogether() {
        EmbeddingProfile profile = EmbeddingProfileTest.profile();
        EmbeddingResult result = new EmbeddingResult(profile, EmbeddingRole.DOCUMENT, List.of(vector()));
        result.require(profile, EmbeddingRole.DOCUMENT, 1);
        assertThat(result.profileId()).isEqualTo(profile.profileId());
        assertThat(result.fingerprint()).isEqualTo(profile.fingerprint());
        assertThatThrownBy(() -> result.require(profile, EmbeddingRole.QUERY, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result.require(profile, EmbeddingRole.DOCUMENT, 2))
                .isInstanceOf(IllegalArgumentException.class);
        EmbeddingProfile different = new EmbeddingProfile(profile.profileId(), profile.provider(),
                profile.modelSource(), profile.model(), "other-revision", 1024, "cosine",
                profile.queryTransformVersion(), profile.documentTransformVersion(), profile.queryTaskType(),
                profile.documentTaskType(), profile.adapterVersion());
        assertThatThrownBy(() -> result.require(different, EmbeddingRole.DOCUMENT, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidVectorsBeforeTheyEnterTheContract() {
        for (float[] invalid : List.of(new float[768], new float[1024])) {
            assertInvalid(invalid);
        }
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            float[] vector = vector();
            vector[1] = invalid;
            assertInvalid(vector);
        }
        assertThatThrownBy(() -> new EmbeddingResult(null, EmbeddingRole.QUERY, List.of(vector())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EmbeddingResult(EmbeddingProfileTest.profile(), null, List.of(vector())))
                .isInstanceOf(NullPointerException.class);
    }

    private void assertInvalid(float[] vector) {
        assertThatThrownBy(() -> new EmbeddingResult(EmbeddingProfileTest.profile(), EmbeddingRole.QUERY,
                List.of(vector))).isInstanceOf(IllegalArgumentException.class);
    }

    private static float[] vector() {
        float[] vector = new float[1024];
        vector[0] = 1f;
        return vector;
    }
}

package com.zhishiku.kb.app.search.expansion;

import com.zhishiku.kb.app.search.model.SearchFailure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateBatchesTest {
    @Test
    void fullBatchHasAtMost128StartsAnd8192DistinctKeysIncludingCrossBatch64CharacterWord() {
        String text = IntStream.range(0, 191).collect(StringBuilder::new,
                (builder, index) -> builder.appendCodePoint(0x4E00 + index), StringBuilder::append).toString();
        var query = query(text);
        var batches = new CandidateBatches(query, CandidateBatchesTest::checkpoint).iterator();
        var first = batches.next();
        assertThat(first.firstBoundary()).isZero();
        assertThat(first.afterBoundary()).isEqualTo(128);
        assertThat(first.keys()).hasSize(8192).doesNotHaveDuplicates();
        assertThat(first.keys()).contains(query.comparisonKey(127, 191));
        assertThat(first.keys()).allSatisfy(key -> assertThat(key.codePointCount(0, key.length())).isBetween(1, 64));
        var second = batches.next();
        assertThat(second.firstBoundary()).isEqualTo(128);
        assertThat(second.afterBoundary()).isEqualTo(191);
        assertThat(second.keys()).doesNotContain(query.comparisonKey(127, 191));
        assertThat(batches.hasNext()).isFalse();
        assertThatThrownBy(batches::next).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> first.keys().add("new")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void deduplicationKeepsAllStartPositionsButDoesNotProbeRepeatedKeysMoreThanOncePerBatch() {
        var batches = new CandidateBatches(query("中".repeat(192)), CandidateBatchesTest::checkpoint).iterator();
        var first = batches.next();
        assertThat(first.afterBoundary() - first.firstBoundary()).isEqualTo(128);
        assertThat(first.keys()).hasSize(64).doesNotHaveDuplicates();
        assertThat(first.keys().getFirst()).isEqualTo("中");
        assertThat(first.keys().getLast()).isEqualTo("中".repeat(64));
        var second = batches.next();
        assertThat(second.firstBoundary()).isEqualTo(128);
        assertThat(second.keys()).containsExactlyElementsOf(first.keys());
        assertThat(first.toString()).doesNotContain("中");
    }

    @Test
    void candidateEndsHonorAsciiBoundariesAndCompleteUnicodeClusters() {
        assertThat(firstKeys("LOAD流程")).doesNotContain("oa").contains("load");
        assertThat(firstKeys("oa流程")).contains("oa");
        assertThat(firstKeys("OA-流程")).contains("oa", "oa-");
        assertThat(firstKeys("OA_流程")).doesNotContain("oa");
        assertThat(firstKeys("👩‍💻流程")).contains("👩‍💻").doesNotContain("👩", "💻");
        assertThat(firstKeys("A\u0315\u0300流程")).contains("À\u0315").doesNotContain("À", "\u0315");
        assertThat(firstKeys("\u0344流程")).contains("\u0308\u0301").doesNotContain("\u0308", "\u0301");
        assertThat(firstKeys("A".repeat(65))).isEmpty();
    }

    @Test
    void longSupplementaryQueryRemainsLazyAndEveryBatchIsBoundedByCodepoints() {
        var query = query("😀".repeat(8000));
        var batches = new CandidateBatches(query, CandidateBatchesTest::checkpoint);
        var positions = new ArrayList<Integer>();
        for (var batch : batches) {
            assertThat(batch.afterBoundary() - batch.firstBoundary()).isBetween(1, 128);
            assertThat(batch.keys().size()).isLessThanOrEqualTo(8192);
            assertThat(batch.keys()).allSatisfy(key -> assertThat(key.codePointCount(0, key.length())).isLessThanOrEqualTo(64));
            positions.add(batch.firstBoundary());
        }
        assertThat(positions).hasSize(63);
        assertThat(positions.getLast()).isEqualTo(7936);
        assertThat(batches.toString()).doesNotContain("😀");
    }

    @Test
    void cancellationStopsBeforeGeneratingTheFollowingBatchAndNeverReturnsPartialKeys() {
        var cancelled = new AtomicBoolean();
        var failure = new SearchFailure(SearchFailure.Kind.CANCELLED);
        var batches = new CandidateBatches(query("中".repeat(192)), () -> {
            if (cancelled.get()) {
                throw failure;
            }
        }).iterator();
        batches.next();
        cancelled.set(true);
        assertThatThrownBy(batches::hasNext).isSameAs(failure);
        assertThatThrownBy(batches::next).isSameAs(failure);
    }

    private static List<String> firstKeys(String text) {
        return new CandidateBatches(query(text), CandidateBatchesTest::checkpoint).iterator().next().keys();
    }

    private static NormalizedQuery query(String text) {
        return NormalizedQuery.create(text, 8000, CandidateBatchesTest::checkpoint);
    }

    private static void checkpoint() {
    }
}

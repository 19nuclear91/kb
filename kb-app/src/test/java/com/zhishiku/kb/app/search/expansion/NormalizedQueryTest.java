package com.zhishiku.kb.app.search.expansion;

import com.zhishiku.kb.app.search.model.SearchFailure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedQueryTest {
    @Test
    void ordinaryChineseAndRepeatedExpressionsRetainIndependentOriginalPositions() {
        var query = normalized("前OA流程，OA使用说明");
        assertThat(query.comparisonText()).isEqualTo("前oa流程，oa使用说明");
        assertThat(query.originalText()).isEqualTo("前OA流程，OA使用说明");
        assertThat(query.originalRange(1, 3)).isEqualTo(new NormalizedQuery.Range(1, 3));
        assertThat(query.originalRange(6, 8)).isEqualTo(new NormalizedQuery.Range(6, 8));
        assertThat(query.matchedText(1, 3)).isEqualTo("OA");
        assertThat(query.matchedText(6, 8)).isEqualTo("OA");
        assertThat(query.eligible(1, 3)).isTrue();
        assertThat(query.eligible(6, 8)).isTrue();
    }

    @Test
    void asciiWordBoundariesRejectEmbeddedAbbreviationsButAllowChineseAndPunctuation() {
        for (String original : List.of("oa流程", "OA-流程", "OA（流程）", "OA", "OA.流程")) {
            var query = normalized(original);
            assertThat(query.comparisonKey(0, 2)).isEqualTo("oa");
            assertThat(query.eligible(0, 2)).isTrue();
        }
        assertThat(normalized("LOAD流程").eligible(1, 3)).isFalse();
        for (String original : List.of("_OA流程", "1OA流程", "aOA流程")) {
            assertThat(normalized(original).eligible(1, 3)).isFalse();
        }
        for (String original : List.of("OAX流程", "OA_流程", "OA1流程")) {
            assertThat(normalized(original).eligible(0, 2)).isFalse();
        }
        assertThat(normalized("中文OA中文").eligible(2, 4)).isTrue();
        assertThat(normalized(".NET流程").eligible(0, 4)).isTrue();
        assertThat(normalized("2.0版本").comparisonKey(0, 3)).isEqualTo("2.0");
    }

    @Test
    void combiningReorderAndCompositionMapEntireClusterToItsUnchangedOriginalText() {
        var query = normalized("x😀A\u0315\u0300OA");
        assertThat(query.comparisonText()).isEqualTo("x😀À\u0315oa");
        assertThat(query.originalLength()).isEqualTo(7);
        assertThat(query.length()).isEqualTo(6);
        assertThat(query.isClusterBoundary(3)).isFalse();
        assertThat(query.originalRange(2, 4)).isEqualTo(new NormalizedQuery.Range(2, 5));
        assertThat(query.matchedText(2, 4)).isEqualTo("A\u0315\u0300");
        assertThat(query.originalRange(4, 6)).isEqualTo(new NormalizedQuery.Range(5, 7));
        assertThat(query.eligible(2, 3)).isFalse();
        assertThatThrownBy(() -> query.originalRange(2, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThat(query.sliceOriginal(0, 7)).isEqualTo(query.originalText());
    }

    @Test
    void normalizationExpansionDoesNotCreateAFalseInteriorOriginalBoundary() {
        var query = normalized("\u0344中");
        assertThat(query.comparisonText()).isEqualTo("\u0308\u0301中");
        assertThat(query.length()).isEqualTo(3);
        assertThat(query.originalLength()).isEqualTo(2);
        assertThat(query.isClusterBoundary(1)).isFalse();
        assertThat(query.originalRange(0, 2)).isEqualTo(new NormalizedQuery.Range(0, 1));
        assertThat(query.matchedText(0, 2)).isEqualTo("\u0344");
        assertThat(query.eligible(0, 1)).isFalse();
    }

    @Test
    void hangulJamoContractsAsOneClusterWithoutMovingFollowingOriginalPositions() {
        var query = normalized("前\u1100\u1161\u11A8后OA");
        assertThat(query.comparisonText()).isEqualTo("前각后oa");
        assertThat(query.originalRange(1, 2)).isEqualTo(new NormalizedQuery.Range(1, 4));
        assertThat(query.matchedText(1, 2)).isEqualTo("\u1100\u1161\u11A8");
        assertThat(query.originalRange(3, 5)).isEqualTo(new NormalizedQuery.Range(5, 7));
        assertThat(query.matchedText(3, 5)).isEqualTo("OA");
    }

    @Test
    void supplementaryEmojiJoinersRegionalPairsAndCrLfCannotBeSplitIntoCandidateFragments() {
        var query = normalized("a👩‍💻🇨🇳\r\nOA");
        assertThat(query.length()).isEqualTo(10);
        assertThat(IntStream.range(0, query.boundaryCount()).map(query::boundaryAt).boxed().toList())
                .containsExactly(0, 1, 4, 6, 8, 9, 10);
        assertThat(query.matchedText(1, 4)).isEqualTo("👩‍💻");
        assertThat(query.matchedText(4, 6)).isEqualTo("🇨🇳");
        assertThat(query.eligible(1, 2)).isFalse();
        assertThat(query.eligible(4, 5)).isFalse();
        assertThat(query.eligible(6, 7)).isFalse();
        var supplementary = normalized("😀OA");
        assertThat(supplementary.originalRange(1, 3)).isEqualTo(new NormalizedQuery.Range(1, 3));
        assertThat(supplementary.matchedText(1, 3)).isEqualTo("OA");
    }

    @Test
    void noWhitespaceWidthScriptOrNonAsciiCaseFoldingIsIntroducedAndDiagnosticIsRedacted() {
        String original = "  OA\t流程／ＯＡ  繁體 Ä ＰＭ * % _ ";
        var query = normalized(original);
        assertThat(query.originalText()).isEqualTo(original);
        assertThat(query.comparisonText()).isEqualTo("  oa\t流程／ＯＡ  繁體 Ä ＰＭ * % _ ");
        assertThat(query.sliceOriginal(0, query.originalLength())).isEqualTo(original);
        assertThat(query.sliceOriginal(0, 0)).isEmpty();
        assertThat(query.toString()).doesNotContain("OA", "流程", "繁體");
        assertThat(query.comparisonText()).doesNotContain("简体", "pm");
    }

    @Test
    void inputAndSpanLimitsUseActualCodepointsAndRejectInvalidUnicodeRatherThanTruncate() {
        assertThat(normalized("😀".repeat(8000)).originalLength()).isEqualTo(8000);
        for (String original : new String[]{null, "", " ", "中\u0000文", "\uD800", "\uDC00", "中".repeat(8001)}) {
            assertThatThrownBy(() -> normalized(original)).isInstanceOfSatisfying(SearchFailure.class,
                    error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.INVALID_INPUT));
        }
        assertThatThrownBy(() -> NormalizedQuery.create("OA", 1, NormalizedQueryTest::checkpoint))
                .isInstanceOf(SearchFailure.class);
        var query = normalized("OA");
        assertThat(query.eligible(-1, 1)).isFalse();
        assertThat(query.eligible(0, 3)).isFalse();
        assertThat(query.eligible(1, 1)).isFalse();
        assertThatThrownBy(() -> query.boundaryAt(query.boundaryCount())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.sliceOriginal(0, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callerCancellationChecksCoverNormalizationAndCoordinateConstruction() {
        var count = new AtomicInteger();
        var failure = new SearchFailure(SearchFailure.Kind.CANCELLED);
        assertThatThrownBy(() -> NormalizedQuery.create("OA流程".repeat(100), 8000, () -> {
            if (count.incrementAndGet() == 12) {
                throw failure;
            }
        })).isSameAs(failure);
        assertThat(count.get()).isEqualTo(12);
        var mappingCount = new AtomicInteger();
        assertThatThrownBy(() -> NormalizedQuery.create("OA", 8000, () -> {
            if (mappingCount.incrementAndGet() == 10) {
                throw failure;
            }
        })).isSameAs(failure);
        assertThat(mappingCount.get()).isEqualTo(10);
    }

    private static NormalizedQuery normalized(String text) {
        return NormalizedQuery.create(text, 8000, NormalizedQueryTest::checkpoint);
    }

    private static void checkpoint() {
    }
}

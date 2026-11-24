package com.zhishiku.kb.app.search.expansion;

import com.zhishiku.kb.app.search.model.SearchInput;
import com.zhishiku.kb.app.search.term.TermInput;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按完整字素簇规范化的不可变查询；比较位置与原文位置均使用 code point。 */
public final class NormalizedQuery {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private final String original;
    private final String comparison;
    private final int[] comparisonPoints;
    private final int[] comparisonOffsets;
    private final int[] originalOffsets;
    private final int[] originalBoundaries;
    private final int[] boundaries;

    /**
     * 原文的半开 code point 区间，不携带文本。
     * @param start 原文起点
     * @param end 原文终点
     */
    public record Range(int start, int end) {
        public Range {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("查询区间无效");
            }
        }
    }

    private record Cluster(int normalizedStart, int normalizedEnd, int originalStart, int originalEnd) {
    }

    private NormalizedQuery(String original, String comparison, List<Cluster> clusters, Runnable checkpoint) {
        this.original = original;
        this.comparison = comparison;
        comparisonPoints = comparison.codePoints().toArray();
        comparisonOffsets = offsets(comparison, checkpoint);
        originalOffsets = offsets(original, checkpoint);
        originalBoundaries = new int[comparisonPoints.length + 1];
        Arrays.fill(originalBoundaries, -1);
        boundaries = new int[clusters.size() + 1];
        originalBoundaries[0] = 0;
        for (int index = 0; index < clusters.size(); index++) {
            checkpoint.run();
            Cluster cluster = clusters.get(index);
            originalBoundaries[cluster.normalizedStart()] = cluster.originalStart();
            originalBoundaries[cluster.normalizedEnd()] = cluster.originalEnd();
            boundaries[index + 1] = cluster.normalizedEnd();
        }
    }

    /** 字符限额由本次搜索配置提供，检查回调沿用调用方的同一截止／取消信号。 */
    public static NormalizedQuery create(String original, int maxCodepoints, Runnable checkpoint) {
        Objects.requireNonNull(checkpoint, "查询检查回调不可为空");
        checkpoint.run();
        SearchInput.validate(original, 1, 1, maxCodepoints);
        checkpoint.run();
        Matcher matcher = GRAPHEME.matcher(original);
        var text = new StringBuilder(original.length());
        var clusters = new ArrayList<Cluster>();
        int originalPosition = 0;
        int normalizedPosition = 0;
        while (matcher.find()) {
            checkpoint.run();
            String cluster = matcher.group();
            String key = TermInput.key(Normalizer.normalize(cluster, Normalizer.Form.NFC));
            int originalEnd = originalPosition + cluster.codePointCount(0, cluster.length());
            int normalizedEnd = normalizedPosition + key.codePointCount(0, key.length());
            text.append(key);
            clusters.add(new Cluster(normalizedPosition, normalizedEnd, originalPosition, originalEnd));
            originalPosition = originalEnd;
            normalizedPosition = normalizedEnd;
            checkpoint.run();
        }
        checkpoint.run();
        return new NormalizedQuery(original, text.toString(), clusters, checkpoint);
    }

    public String originalText() {
        return original;
    }

    public String comparisonText() {
        return comparison;
    }

    public int length() {
        return comparisonPoints.length;
    }

    public int originalLength() {
        return originalOffsets.length - 1;
    }

    public int boundaryCount() {
        return boundaries.length;
    }

    public int boundaryAt(int index) {
        if (index < 0 || index >= boundaries.length) {
            throw new IllegalArgumentException("查询边界无效");
        }
        return boundaries[index];
    }

    public boolean isClusterBoundary(int position) {
        return position >= 0 && position < originalBoundaries.length && originalBoundaries[position] >= 0;
    }

    /** 候选必须覆盖完整字素簇，ASCII 字母／数字／下划线不能从同类字符内部命中。 */
    public boolean eligible(int start, int end) {
        if (!validSpan(start, end)) {
            return false;
        }
        return !(asciiWord(comparisonPoints[start]) && start > 0 && asciiWord(comparisonPoints[start - 1]))
                && !(asciiWord(comparisonPoints[end - 1]) && end < length() && asciiWord(comparisonPoints[end]));
    }

    public String comparisonKey(int start, int end) {
        requireSpan(start, end);
        return comparison.substring(comparisonOffsets[start], comparisonOffsets[end]);
    }

    public Range originalRange(int start, int end) {
        requireSpan(start, end);
        return new Range(originalBoundaries[start], originalBoundaries[end]);
    }

    public String matchedText(int start, int end) {
        Range range = originalRange(start, end);
        return sliceOriginal(range.start(), range.end());
    }

    /** 原文重建时允许空片段，绝不把比较文本替代未修改的原文。 */
    public String sliceOriginal(int start, int end) {
        if (start < 0 || end < start || end > originalLength()) {
            throw new IllegalArgumentException("查询原文区间无效");
        }
        return original.substring(originalOffsets[start], originalOffsets[end]);
    }

    private boolean validSpan(int start, int end) {
        return start >= 0 && end > start && end <= length() && isClusterBoundary(start) && isClusterBoundary(end);
    }

    private void requireSpan(int start, int end) {
        if (!validSpan(start, end)) {
            throw new IllegalArgumentException("查询字素区间无效");
        }
    }

    private static boolean asciiWord(int codepoint) {
        return codepoint >= 'a' && codepoint <= 'z' || codepoint >= 'A' && codepoint <= 'Z'
                || codepoint >= '0' && codepoint <= '9' || codepoint == '_';
    }

    private static int[] offsets(String text, Runnable checkpoint) {
        int[] result = new int[text.codePointCount(0, text.length()) + 1];
        int position = 0;
        for (int index = 0; index < text.length();) {
            checkpoint.run();
            index += Character.charCount(text.codePointAt(index));
            result[++position] = index;
        }
        return result;
    }

    @Override
    public String toString() {
        return "NormalizedQuery[redacted]";
    }
}

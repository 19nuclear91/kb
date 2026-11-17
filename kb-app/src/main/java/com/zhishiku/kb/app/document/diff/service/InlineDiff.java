package com.zhishiku.kb.app.document.diff.service;

import com.zhishiku.kb.app.document.diff.model.DiffViews.Segment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class InlineDiff {
    private InlineDiff() {
    }

    public static List<Segment> compare(String before, String after, DiffDeadline deadline) {
        var a = before.codePoints().boxed().toList();
        var b = after.codePoints().boxed().toList();
        List<Segment> segments = new ArrayList<>();
        for (var edit : Myers.diff(a, b, deadline)) {
            deadline.check();
            int point = edit.before() >= 0 ? a.get(edit.before()) : b.get(edit.after());
            append(segments, edit.type(), new String(Character.toChars(point)));
        }
        for (int i = 1; i + 1 < segments.size(); i++) {
            Segment equal = segments.get(i);
            if ("EQUAL".equals(equal.type()) && equal.text().codePointCount(0, equal.text().length()) < 2
                    && !"EQUAL".equals(segments.get(i - 1).type()) && !"EQUAL".equals(segments.get(i + 1).type())) {
                List<Segment> rebuilt = new ArrayList<>();
                for (int j = 0; j < segments.size(); j++) {
                    deadline.check();
                    Segment current = segments.get(j);
                    if (j == i) {
                        append(rebuilt, "DELETE", current.text());
                        append(rebuilt, "INSERT", current.text());
                    } else {
                        append(rebuilt, current.type(), current.text());
                    }
                }
                segments = rebuilt;
            }
        }
        // 连续修改统一输出删除后插入，保持两侧各自字符顺序。
        List<Segment> result = new ArrayList<>();
        StringBuilder deleted = new StringBuilder();
        StringBuilder inserted = new StringBuilder();
        for (Segment segment : segments) {
            deadline.check();
            if ("EQUAL".equals(segment.type())) {
                append(result, "DELETE", deleted.toString());
                append(result, "INSERT", inserted.toString());
                deleted.setLength(0);
                inserted.setLength(0);
                append(result, "EQUAL", segment.text());
            } else if ("DELETE".equals(segment.type())) {
                deleted.append(segment.text());
            } else {
                inserted.append(segment.text());
            }
        }
        append(result, "DELETE", deleted.toString());
        append(result, "INSERT", inserted.toString());
        return List.copyOf(result);
    }

    private static void append(List<Segment> output, String type, String text) {
        if (text.isEmpty()) {
            return;
        }
        if (!output.isEmpty() && output.getLast().type().equals(type)) {
            Segment previous = output.removeLast();
            output.add(new Segment(type, previous.text() + text));
        } else {
            output.add(new Segment(type, text));
        }
    }

    public static double dice(String before, String after, DiffDeadline deadline) {
        if (before.equals(after)) {
            return 1;
        }
        int[] a = before.codePoints().toArray();
        int[] b = after.codePoints().toArray();
        if (a.length < 2 || b.length < 2) {
            return 0;
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (int i = 1; i < a.length; i++) {
            deadline.check();
            counts.merge(((long) a[i - 1] << 32) | a[i], 1, Integer::sum);
        }
        int common = 0;
        for (int i = 1; i < b.length; i++) {
            deadline.check();
            long key = ((long) b[i - 1] << 32) | b[i];
            int count = counts.getOrDefault(key, 0);
            if (count > 0) {
                common++;
                counts.put(key, count - 1);
            }
        }
        return 2.0 * common / (a.length + b.length - 2);
    }
}

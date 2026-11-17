package com.zhishiku.kb.app.document.diff.service;

import com.zhishiku.kb.app.document.diff.DocumentDiffProperties;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Computed;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Operation;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Segment;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Side;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Stats;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Table;
import com.zhishiku.kb.app.document.diff.model.SnapshotBlock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlockDiff {
    private final DocumentDiffProperties properties;

    public BlockDiff(DocumentDiffProperties properties) {
        this.properties = properties;
    }

    public Computed compare(List<SnapshotBlock> base, List<SnapshotBlock> target, DiffDeadline deadline) {
        var edits = Myers.diff(base.stream().map(BlockDiff::key).toList(),
                target.stream().map(BlockDiff::key).toList(), deadline);
        List<Operation> operations = new ArrayList<>();
        int cursor = 0;
        while (cursor < edits.size()) {
            deadline.check();
            var edit = edits.get(cursor);
            if ("EQUAL".equals(edit.type())) {
                operations.add(operation("EQUAL", base.get(edit.before()), target.get(edit.after()), deadline));
                cursor++;
            } else {
                List<SnapshotBlock> removed = new ArrayList<>();
                List<SnapshotBlock> added = new ArrayList<>();
                while (cursor < edits.size() && !"EQUAL".equals(edits.get(cursor).type())) {
                    deadline.check();
                    var changed = edits.get(cursor++);
                    if (changed.before() >= 0) {
                        removed.add(base.get(changed.before()));
                    } else {
                        added.add(target.get(changed.after()));
                    }
                }
                pair(removed, added, operations, deadline);
            }
        }
        operations = moves(operations, deadline);
        int equal = 0, added = 0, removed = 0, modified = 0, moved = 0;
        double aligned = 0;
        for (Operation op : operations) {
            deadline.check();
            switch (op.op()) {
                case "EQUAL" -> {
                    equal++;
                    aligned += length(op.base().text());
                }
                case "MOVED" -> {
                    moved++;
                    aligned += length(op.base().text());
                }
                case "ADDED" -> added++;
                case "REMOVED" -> removed++;
                case "MODIFIED" -> {
                    modified++;
                    if (!op.segments().isEmpty()) {
                        aligned += op.segments().stream().filter(segment -> "EQUAL".equals(segment.type()))
                                .mapToInt(segment -> length(segment.text())).sum();
                    } else {
                        aligned += InlineDiff.dice(op.base().text(), op.target().text(), deadline)
                                * Math.min(length(op.base().text()), length(op.target().text()));
                    }
                }
                default -> throw new IllegalStateException("DIFF_OPERATION_INVALID");
            }
        }
        long maximum = Math.max(base.stream().mapToLong(block -> length(block.text())).sum(),
                target.stream().mapToLong(block -> length(block.text())).sum());
        return new Computed(List.copyOf(operations), new Stats(equal, added, removed, modified, moved),
                maximum == 0 ? 1 : Math.min(1, aligned / maximum));
    }

    private void pair(List<SnapshotBlock> a, List<SnapshotBlock> b, List<Operation> out, DiffDeadline deadline) {
        if ((long) a.size() * b.size() > properties.pairingMaxCells()) {
            for (var block : a) {
                out.add(operation("REMOVED", block, null, deadline));
            }
            for (var block : b) {
                out.add(operation("ADDED", null, block, deadline));
            }
            return;
        }
        double[][] scores = new double[a.size() + 1][b.size() + 1];
        boolean[][] paired = new boolean[a.size()][b.size()];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                deadline.check();
                double dice = a.get(i).kind().equals(b.get(j).kind())
                        ? InlineDiff.dice(a.get(i).text(), b.get(j).text(), deadline) : 0;
                double skip = Math.max(scores[i + 1][j], scores[i][j + 1]);
                paired[i][j] = dice >= 0.5 && dice + scores[i + 1][j + 1] >= skip;
                scores[i][j] = paired[i][j] ? dice + scores[i + 1][j + 1] : skip;
            }
        }
        int i = 0, j = 0;
        while (i < a.size() || j < b.size()) {
            deadline.check();
            if (i < a.size() && j < b.size() && paired[i][j]) {
                out.add(operation("MODIFIED", a.get(i++), b.get(j++), deadline));
            } else if (i < a.size() && (j == b.size() || scores[i + 1][j] >= scores[i][j + 1])) {
                out.add(operation("REMOVED", a.get(i++), null, deadline));
            } else {
                out.add(operation("ADDED", null, b.get(j++), deadline));
            }
        }
    }

    private Operation operation(String op, SnapshotBlock base, SnapshotBlock target, DiffDeadline deadline) {
        deadline.check();
        SnapshotBlock block = target == null ? base : target;
        List<Segment> segments = List.of();
        boolean omitted = false;
        Table table = null;
        if ("MODIFIED".equals(op)) {
            if ("TABLE".equals(block.kind())) {
                table = TableDiff.compare(base.tableCellsJson(), target.tableCellsJson(), deadline);
            } else if (Math.max(length(base.text()), length(target.text())) > properties.inlineDiffMaxChars()) {
                omitted = true;
            } else {
                segments = InlineDiff.compare(base.text(), target.text(), deadline);
            }
        }
        return new Operation(op, block.kind(), block.headingPath(), side(base), side(target), segments, omitted, table);
    }

    private static List<Operation> moves(List<Operation> operations, DiffDeadline deadline) {
        Map<String, ArrayDeque<Integer>> removed = new HashMap<>();
        for (int i = 0; i < operations.size(); i++) {
            Operation op = operations.get(i);
            if ("REMOVED".equals(op.op())) {
                removed.computeIfAbsent(op.kind() + "\u0000" + op.base().text(), ignored -> new ArrayDeque<>()).add(i);
            }
        }
        Set<Integer> consumed = new HashSet<>();
        List<Operation> result = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            deadline.check();
            Operation op = operations.get(i);
            var matches = "ADDED".equals(op.op()) ? removed.get(op.kind() + "\u0000" + op.target().text()) : null;
            if (matches != null && !matches.isEmpty()) {
                int index = matches.removeFirst();
                consumed.add(index);
                result.add(new Operation("MOVED", op.kind(), op.headingPath(), operations.get(index).base(),
                        op.target(), List.of(), false, null));
            } else {
                result.add(op);
            }
        }
        List<Operation> finalResult = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            if (!consumed.contains(i)) {
                finalResult.add(result.get(i));
            }
        }
        return finalResult;
    }

    private static String key(SnapshotBlock block) {
        return block.kind() + "\u0000" + block.text();
    }

    private static Side side(SnapshotBlock block) {
        return block == null ? null : new Side(block.index(), block.pageNo(), block.text());
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}

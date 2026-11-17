package com.zhishiku.kb.app.document.diff.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Cell;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Row;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TableDiff {
    private static final ObjectMapper JSON = new ObjectMapper();

    private TableDiff() {
    }

    public static Table compare(String before, String after, DiffDeadline deadline) {
        List<List<String>> a = parse(before);
        List<List<String>> b = parse(after);
        int columns = a.isEmpty() ? -1 : a.getFirst().size();
        if (columns <= 0 || a.stream().anyMatch(row -> row.size() != columns)
                || b.isEmpty() || b.stream().anyMatch(row -> row.size() != columns)) {
            return new Table("RESTRUCTURED", List.of(), a, b);
        }
        List<Row> rows = new ArrayList<>();
        var edits = Myers.diff(a, b, deadline);
        int i = 0;
        while (i < edits.size()) {
            deadline.check();
            var edit = edits.get(i);
            if ("EQUAL".equals(edit.type())) {
                rows.add(row(a, b, edit.before(), edit.after(), "EQUAL", deadline));
                i++;
            } else {
                List<Integer> deleted = new ArrayList<>();
                List<Integer> added = new ArrayList<>();
                while (i < edits.size() && !"EQUAL".equals(edits.get(i).type())) {
                    deadline.check();
                    var changed = edits.get(i++);
                    if (changed.before() >= 0) {
                        deleted.add(changed.before());
                    } else {
                        added.add(changed.after());
                    }
                }
                for (int j = 0; j < Math.max(deleted.size(), added.size()); j++) {
                    Integer old = j < deleted.size() ? deleted.get(j) : null;
                    Integer target = j < added.size() ? added.get(j) : null;
                    rows.add(row(a, b, old, target, old == null ? "ADDED"
                            : target == null ? "REMOVED" : "MODIFIED", deadline));
                }
            }
        }
        return new Table("CELLS", List.copyOf(rows), a, b);
    }

    private static Row row(List<List<String>> a, List<List<String>> b, Integer old, Integer target,
                           String op, DiffDeadline deadline) {
        List<Cell> cells = new ArrayList<>();
        if ("MODIFIED".equals(op)) {
            for (int column = 0; column < a.get(old).size(); column++) {
                deadline.check();
                if (!Objects.equals(a.get(old).get(column), b.get(target).get(column))) {
                    cells.add(new Cell(column, a.get(old).get(column), b.get(target).get(column)));
                }
            }
        }
        return new Row(op, old, target, old == null ? null : a.get(old),
                target == null ? null : b.get(target), List.copyOf(cells));
    }

    private static List<List<String>> parse(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            List<List<String>> rows = JSON.readValue(json, new TypeReference<>() { });
            return rows.stream().map(List::copyOf).toList();
        } catch (Exception failure) {
            throw new IllegalStateException("DIFF_SNAPSHOT_INVALID");
        }
    }
}

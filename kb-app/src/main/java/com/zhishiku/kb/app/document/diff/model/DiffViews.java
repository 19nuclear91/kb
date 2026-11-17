package com.zhishiku.kb.app.document.diff.model;

import java.util.List;

public final class DiffViews {
    private DiffViews() {
    }

    public record Side(int index, Integer pageNo, String text) {
    }

    public record Segment(String type, String text) {
    }

    public record Cell(int column, String before, String after) {
    }

    public record Row(String op, Integer baseIndex, Integer targetIndex, List<String> before,
                      List<String> after, List<Cell> cells) {
    }

    public record Table(String status, List<Row> rows, List<List<String>> base, List<List<String>> target) {
    }

    public record Operation(String op, String kind, String headingPath, Side base, Side target,
                            List<Segment> segments, boolean segmentsOmitted, Table table) {
    }

    public record Stats(int unchanged, int added, int removed, int modified, int moved) {
    }

    public record ValueChange(String type, String before, String after, int operationIndex, String headingPath) {
    }

    public record Computed(List<Operation> operations, Stats stats, double similarity) {
    }

    public record Version(long versionId, int versionNo, String parserName, String parserVersion, boolean truncated) {
    }

    public record Warning(String code, String side) {
    }

    public record Response(long documentId, Version base, Version target, String status, List<String> missingSides,
                           List<Warning> warnings, Stats stats, List<Operation> operations, List<ValueChange> valueChanges) {
    }
}

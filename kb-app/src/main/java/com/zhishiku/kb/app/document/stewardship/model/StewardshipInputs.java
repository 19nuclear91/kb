package com.zhishiku.kb.app.document.stewardship.model;

public final class StewardshipInputs {
    private StewardshipInputs() {
    }

    public record Update(Long responsibleUserId, Integer cycleMonths, long expectedStewardshipVersion) {
    }

    public record Confirm(long expectedStewardshipVersion) {
    }

    public record Candidates(String nameQuery, int limit, String cursor) {
    }
}

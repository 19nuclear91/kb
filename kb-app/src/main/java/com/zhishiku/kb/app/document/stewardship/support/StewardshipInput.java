package com.zhishiku.kb.app.document.stewardship.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipInputs;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipSchedule;

import java.util.Set;

public final class StewardshipInput {
    private StewardshipInput() {
    }

    public static StewardshipInputs.Candidates candidates(ObjectNode body) {
        ValidityInput.fields(body, Set.of("nameQuery", "limit", "cursor"), Set.of());
        String name = body.has("nameQuery") ? ValidityInput.text(body.get("nameQuery"), 64, false, true, true) : "";
        return new StewardshipInputs.Candidates(name, ValidityInput.limit(body.get("limit")), ValidityInput.cursor(body.get("cursor")));
    }

    public static StewardshipInputs.Update update(ObjectNode body) {
        var keys = Set.of("responsibleUserId", "cycleMonths", "expectedStewardshipVersion");
        ValidityInput.fields(body, keys, keys);
        Long responsible = ValidityInput.nullableId(body.get("responsibleUserId"));
        Integer cycle = body.get("cycleMonths").isNull() ? null : (int) ValidityInput.number(body.get("cycleMonths"), 1, 24);
        if ((responsible == null) != (cycle == null)) {
            throw ValidityInput.invalid();
        }
        if (cycle != null) {
            try {
                StewardshipSchedule.requireCycle(cycle);
            } catch (IllegalArgumentException ignored) {
                throw ValidityInput.invalid();
            }
        }
        return new StewardshipInputs.Update(responsible, cycle, ValidityInput.number(body.get("expectedStewardshipVersion"), 0, Long.MAX_VALUE));
    }

    public static StewardshipInputs.Confirm confirm(ObjectNode body) {
        ValidityInput.fields(body, Set.of("expectedStewardshipVersion"), Set.of("expectedStewardshipVersion"));
        return new StewardshipInputs.Confirm(ValidityInput.number(body.get("expectedStewardshipVersion"), 0, Long.MAX_VALUE));
    }
}

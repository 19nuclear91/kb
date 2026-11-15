package com.zhishiku.kb.app.document.applicability;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** 相同数据供状态单元测试、Mapper 参数测试和首次真实库核对使用。 */
public final class ValidityBoundaryCases {
    private ValidityBoundaryCases() {
    }

    public static Stream<List<String>> cases(String kind) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                ValidityBoundaryCases.class.getResourceAsStream("/validity/state-boundaries.csv")), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> line.startsWith(kind + ","))
                    .map(line -> List.of(line.split(",", -1))).toList().stream();
        }
    }

    public static LocalDate date(String value) {
        return value.isEmpty() ? null : LocalDate.parse(value);
    }
}

package com.zhishiku.kb.eval.report;

import com.zhishiku.kb.eval.model.InputModel.SourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvReportWriterTest {
    @Test
    void roundTripsSummaryAndCasesWithNullCellsAndFullPrecisionRetainedInModel() {
        var report = ReportFixture.report();
        CsvReportWriter writer = new CsvReportWriter();
        List<List<String>> rows = parse(writer.summary(report));
        List<String> recall = rows.stream().filter(row -> row.get(0).equals("z_candidate") && row.get(2).equals(
                "OVERALL")
                && row.get(5).equals("recall")).findFirst().orElseThrow();
        assertThat(recall).containsExactly("z_candidate", "RECORDED", "OVERALL", "", "2", "recall", "5", "1",
                "0.666667", "a_base", "1", "0.800000", "-0.133333");
        var duplicate = rows.stream().filter(row -> row.get(0).equals("z_candidate") && row.get(2).equals("OVERALL")
                && row.get(5).equals("duplicateRate")).findFirst().orElseThrow();
        assertThat(duplicate.get(8)).isEmpty();
        assertThat(duplicate.get(7)).isEqualTo("0");
        var cases = parse(writer.cases(report));
        assertThat(cases.getFirst()).contains("caseId", "rawDelta");
        assertThat(cases).anySatisfy(row -> assertThat(row).contains("c1", "a_tag", "0.666667"));
        assertThat(report.runs().get(1).overall().metrics().getFirst().value()).isEqualTo(2.0 / 3);
    }

    @Test
    void csvEscapingPreservesQuotesCommasAndNewlines() {
        List<String> values = List.of("plain", "a,b", "a\"b", "a\r\nb", "");
        String csv = String.join(",", values.stream().map(CsvReportWriter::escape).toList()) + "\r\n";
        assertThat(parse(csv)).containsExactly(values);
    }

    @Test
    void stableOrderingIgnoresInputOrderForRunsGroupsCasesAndK() {
        var a = ReportFixture.run("a", SourceType.RECORDED, 0.5);
        var z = ReportFixture.run("z", SourceType.SYNTHETIC, 0.6);
        assertThat(ReportOrdering.runs(List.of(z, a))).containsExactly(a, z);
        assertThat(ReportOrdering.runs(List.of(a, z))).containsExactly(a, z);
        assertThat(a.groups()).extracting(group -> group.tag()).containsExactly("a_tag", "z_tag");
        assertThat(a.cases()).extracting(sample -> sample.caseId()).containsExactly("c1", "c2");
        assertThat(ReportFixture.report().ks()).containsExactly(5, 10);
        assertThat(CsvReportWriter.number(0.69999997)).isEqualTo(CsvReportWriter.number(0.7));
    }

    /**
     * 独立 CSV 解码器，核对序列化往返而非对比实现生成的预期。
     */
    private static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && (c == ',' || c == '\r' || c == '\n')) {
                row.add(field.toString());
                field.setLength(0);
                if (c != ',') {
                    rows.add(List.copyOf(row));
                    row.clear();
                    if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                        i++;
                    }
                }
            } else {
                field.append(c);
            }
        }
        return rows;
    }
}

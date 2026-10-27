package com.zhishiku.kb.eval.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 四种固定文件名，由 OutputPublisher 控制目录发布。
 */
public final class ReportWriter {
    public void write(Path directory, EvaluationReport report) throws IOException {
        CsvReportWriter csv = new CsvReportWriter();
        writeFile(directory, "summary.json", new JsonReportWriter().serialize(report));
        writeFile(directory, "summary.csv", csv.summary(report));
        writeFile(directory, "cases.csv", csv.cases(report));
        writeFile(directory, "report.md", new MarkdownReportWriter().render(report));
    }

    private static void writeFile(Path directory, String name, String content) throws IOException {
        Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}

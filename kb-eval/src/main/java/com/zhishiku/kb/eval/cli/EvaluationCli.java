package com.zhishiku.kb.eval.cli;

import com.zhishiku.kb.eval.comparison.ComparisonCalculator;
import com.zhishiku.kb.eval.comparison.QualityGate;
import com.zhishiku.kb.eval.comparison.ThresholdReader;
import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.input.EvaluationException.Code;
import com.zhishiku.kb.eval.input.InputReader;
import com.zhishiku.kb.eval.metrics.Aggregator;
import com.zhishiku.kb.eval.metrics.MetricModel.RunMetrics;
import com.zhishiku.kb.eval.report.OutputPublisher;
import com.zhishiku.kb.eval.report.ReportFactory;
import com.zhishiku.kb.eval.report.ReportWriter;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * 离线评测的进程入口。
 */
public final class EvaluationCli {
    private static final String HELP = """
            离线检索质量评测（仅本地文件，不调用检索服务）
            用法：java -jar kb-eval.jar --dataset <dataset.json> --run <run.json> [--run <run.json> ...]
                  --output <新目录> [--baseline <runId>] [--k <5,10,20>] [--thresholds <thresholds.json>]
                  --help
            默认 k：5,10,20。退出码：0 通过或未配置门禁；1 质量门禁失败且保留报告；2 输入、配置或输出错误。
            来源与配置由输入声明，文档版本命中不保证分块包含答案。
            """;

    private EvaluationCli() {
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err));
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        try {
            CliOptions options = CliOptions.parse(args);
            if (options.help()) {
                out.print(HELP);
                return 0;
            }
            Path output = OutputPublisher.outputPath(options.output());
            var inputs = new InputReader().read(options.dataset(), options.runs(), options.ks().getLast());
            Aggregator aggregator = new Aggregator();
            List<RunMetrics> runs = inputs.runs().stream().map(run -> aggregator.calculate(inputs.dataset(), run,
                    options.ks())).toList();
            var comparisons = new ComparisonCalculator().compare(runs, options.baseline());
            var thresholds = options.thresholds() == null ? null : new ThresholdReader().read(options.thresholds(),
                    options.ks(), options.baseline());
            var gate = new QualityGate().evaluate(runs, options.baseline(), thresholds);
            var report = new ReportFactory().create(inputs.dataset(), options.ks(), runs, options.baseline(),
                    comparisons, thresholds, gate);
            new OutputPublisher().publish(output, staging -> new ReportWriter().write(staging, report));
            out.println(gate.passed() ? "EVAL_COMPLETE" : "EVAL_QUALITY_GATE_FAILED");
            return ExitCodes.forGate(gate);
        } catch (EvaluationException e) {
            err.println(DiagnosticFormatter.format(e));
            return ExitCodes.forError();
        } catch (RuntimeException e) {
            err.println(DiagnosticFormatter.format(new EvaluationException(Code.OUTPUT_FAILED, "output", 0, "output")));
            return ExitCodes.forError();
        }
    }
}

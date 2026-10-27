package com.zhishiku.kb.eval.cli;

import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.input.EvaluationException.Code;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 固定参数集合，不在解析阶段读文件。
 *
 * @param dataset    数据集清单路径
 * @param runs       运行清单路径
 * @param baseline   显式基线标识，未指定时为空
 * @param ks         升序分块预算
 * @param thresholds 阈值文件路径，未指定时为空
 * @param output     新报告目录
 * @param help       是否只展示帮助
 */
public record CliOptions(String dataset, List<String> runs, String baseline, List<Integer> ks,
                         String thresholds, String output, boolean help) {
    private static final Set<String> OPTIONS = Set.of("--dataset", "--run", "--baseline", "--k",
            "--thresholds", "--output", "--help");

    public CliOptions {
        runs = List.copyOf(runs);
        ks = List.copyOf(ks);
    }

    public static CliOptions parse(String[] args) {
        Map<String, String> singles = new TreeMap<>();
        List<String> runs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (!OPTIONS.contains(option) || (!option.equals("--run") && !seen.add(option))) {
                throw invalid();
            }
            if (option.equals("--help")) {
                help = true;
                continue;
            }
            if (++i == args.length || args[i].isBlank() || args[i].startsWith("--")) {
                throw invalid();
            }
            if (option.equals("--run")) {
                runs.add(args[i]);
            } else {
                singles.put(option, args[i]);
            }
        }
        if (!help && (!singles.containsKey("--dataset") || !singles.containsKey("--output") || runs.isEmpty())) {
            throw invalid();
        }
        String baseline = singles.get("--baseline");
        if (baseline != null && !baseline.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw invalid();
        }
        List<Integer> ks = List.of(5, 10, 20);
        if (singles.containsKey("--k")) {
            TreeSet<Integer> values = new TreeSet<>();
            for (String part : singles.get("--k").split(",", -1)) {
                try {
                    if (!part.matches("[0-9]+")) {
                        throw invalid();
                    }
                    int k = Integer.parseInt(part);
                    if (k <= 0 || !values.add(k)) {
                        throw invalid();
                    }
                } catch (NumberFormatException e) {
                    throw invalid();
                }
            }
            ks = List.copyOf(values);
        }
        return new CliOptions(singles.get("--dataset"), runs, baseline, ks,
                singles.get("--thresholds"), singles.get("--output"), help);
    }

    private static EvaluationException invalid() {
        return new EvaluationException(Code.ARGUMENT, "cli", 0, "arguments");
    }
}

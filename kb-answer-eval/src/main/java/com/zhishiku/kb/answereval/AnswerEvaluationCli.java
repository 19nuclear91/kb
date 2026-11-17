package com.zhishiku.kb.answereval;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 独立 CLI；入口可在进程内测试，帮助和参数校验不加载凭据。 */
public final class AnswerEvaluationCli {
    private static final Set<String> COMMANDS = Set.of(
            "validate-dataset", "collect", "judge", "review-template", "review-import", "report");

    private AnswerEvaluationCli() {
    }

    public record Options(String command, Map<String, String> values) {
        public Options {
            values = Map.copyOf(values);
        }

        public String value(String name) {
            return values.get("--" + name);
        }

        @Override
        public String toString() {
            return "Options[command=" + command + "]";
        }
    }

    @FunctionalInterface
    public interface Commands {
        int execute(Options options);
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err, new EvaluationCommands()));
    }

    public static int execute(String[] args, PrintStream out, PrintStream err, Commands commands) {
        try {
            if (args.length == 1 && "--help".equals(args[0])) {
                out.println("回答质量与引用证据评测：validate-dataset / collect / judge / review-template / review-import / report");
                out.println("使用 <命令> --help 查看参数。退出码：0 PASS/NOT_CONFIGURED，1 FAIL，2 INVALID，3 INCOMPLETE，4 REVIEW_REQUIRED。");
                return 0;
            }
            if (args.length == 0 || !COMMANDS.contains(args[0])) {
                throw new EvaluationFailure(EvaluationFailure.Code.ARGUMENT);
            }
            if (args.length == 2 && "--help".equals(args[1])) {
                out.println(args[0] + " 必需参数：" + required(args[0]).stream().sorted().toList());
                out.println("各参数接一个本地文件或目录；report 可选 --reviews、--baseline，review-import 可选 --history。");
                return 0;
            }
            int status = commands.execute(parse(args));
            out.println("ANSWER_EVAL_EXIT " + status);
            return status;
        } catch (EvaluationFailure failure) {
            err.println(failure.getMessage());
            return failure.code() == EvaluationFailure.Code.INCOMPLETE ? 3 : 2;
        } catch (RuntimeException failure) {
            err.println("ANSWER_EVAL_ERROR INTERNAL");
            return 2;
        }
    }

    static Options parse(String[] args) {
        Set<String> required = required(args[0]);
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            String key = args[i];
            boolean optional = "report".equals(args[0]) && Set.of("--reviews", "--baseline").contains(key)
                    || "review-import".equals(args[0]) && "--history".equals(key);
            if ((!required.contains(key) && !optional) || i + 1 >= args.length
                    || args[i + 1].isBlank() || args[i + 1].startsWith("--")
                    || values.putIfAbsent(key, args[i + 1]) != null) {
                throw new EvaluationFailure(EvaluationFailure.Code.ARGUMENT);
            }
        }
        if (!values.keySet().containsAll(required)) {
            throw new EvaluationFailure(EvaluationFailure.Code.ARGUMENT);
        }
        return new Options(args[0], values);
    }

    private static Set<String> required(String command) {
        return switch (command) {
            case "validate-dataset" -> Set.of("--dataset");
            case "collect" -> Set.of("--dataset", "--config", "--output");
            case "judge" -> Set.of("--dataset", "--collection", "--config", "--output");
            case "review-template", "report" -> Set.of("--dataset", "--collection", "--judgments", "--config", "--output");
            case "review-import" -> Set.of("--dataset", "--collection", "--judgments", "--reviews", "--config", "--output");
            default -> throw new EvaluationFailure(EvaluationFailure.Code.ARGUMENT);
        };
    }
}

package com.zhishiku.kb.eval.collector;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 独立采集器入口，不加载业务应用。 */
public final class CollectionCli {
    private CollectionCli() {
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err, new Collector()));
    }

    /**
     * 命令行输入位置。
     * @param dataset 数据集清单路径
     * @param cases 用例路径
     * @param config 配置路径
     * @param output 尚不存在的输出目录
     */
    public record Options(String dataset, String cases, String config, String output) {
    }

    @FunctionalInterface
    public interface Runner {
        void run(Options options);
    }

    public static int execute(String[] args, PrintStream out, PrintStream err, Runner runner) {
        try {
            if (args.length == 1 && args[0].equals("--help")) {
                out.println("用法：--dataset <dataset.json> --cases <cases.jsonl> --config <collection-config.json> --output <新目录>");
                out.println("--help  显示帮助；退出码：0 完整成功，2 本地失败，3 身份、HTTP 或协议失败。");
                return 0;
            }
            runner.run(arguments(args));
            out.println("COLLECT_COMPLETE");
            return 0;
        } catch (CollectionException e) {
            err.println(e.getMessage());
            return e.code().exitCode();
        } catch (RuntimeException e) {
            err.println("COLLECT_ERROR LOCAL role=cli line=0 field=operation");
            return 2;
        }
    }

    static Options arguments(String[] args) {
        Set<String> allowed = Set.of("--dataset", "--cases", "--config", "--output");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i];
            if (!allowed.contains(name) || i + 1 >= args.length || args[i + 1].isBlank()
                    || args[i + 1].startsWith("--") || values.putIfAbsent(name, args[i + 1]) != null) {
                throw new CollectionException(CollectionException.Code.ARGUMENT, "cli", 0, "arguments");
            }
        }
        if (!values.keySet().equals(allowed)) {
            throw new CollectionException(CollectionException.Code.ARGUMENT, "cli", 0, "arguments");
        }
        return new Options(values.get("--dataset"), values.get("--cases"), values.get("--config"), values.get("--output"));
    }
}

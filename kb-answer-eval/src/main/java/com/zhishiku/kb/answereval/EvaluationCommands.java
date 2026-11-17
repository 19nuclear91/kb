package com.zhishiku.kb.answereval;

/** 各阶段的组合入口；离线阶段不创建 HTTP 客户端或读取凭据。 */
public final class EvaluationCommands implements AnswerEvaluationCli.Commands {
    @Override
    public int execute(AnswerEvaluationCli.Options options) {
        DatasetReader.Batch dataset = new DatasetReader().read(options.value("dataset"));
        if ("validate-dataset".equals(options.command())) {
            return 0;
        }
        if ("collect".equals(options.command())) {
            var config = StrictJson.read(LocalFiles.read(LocalFiles.path(options.value("config")),
                    StrictJson.MANIFEST_LIMIT).bytes(), CollectionConfig.class);
            var credentials = config.credentials(dataset, System::getenv);
            var output = new OutputPublisher().preflight(options.value("output"));
            try (var transport = new JdkTransport(config.connectTimeoutMs())) {
                return new CollectionRunner().collect(dataset, config, credentials, transport, output);
            }
        }
        var collection = new CollectionReader().read(options.value("collection"), dataset);
        if ("judge".equals(options.command())) {
            var config = StrictJson.read(LocalFiles.read(LocalFiles.path(options.value("config")),
                    StrictJson.MANIFEST_LIMIT).bytes(), JudgeConfig.class);
            dataset.requireTarget(config.targetId(), true);
            String key = System.getenv(config.apiKeyEnv());
            var output = new OutputPublisher().preflight(options.value("output"));
            try (var transport = new JdkTransport(config.connectTimeoutMs())) {
                return new JudgeRunner().judge(dataset, collection, config, key, transport, System::nanoTime, output);
            }
        }
        var judgments = new JudgmentReader().read(options.value("judgments"), dataset, collection);
        var config = StrictJson.read(LocalFiles.read(LocalFiles.path(options.value("config")),
                StrictJson.MANIFEST_LIMIT).bytes(), ReportConfig.class);
        var reviews = new Reviews();
        if ("review-template".equals(options.command())) {
            reviews.template(dataset, collection, judgments, config, new OutputPublisher().preflight(options.value("output")));
            return 0;
        }
        if ("review-import".equals(options.command())) {
            var history = reviews.read(options.value("history"), dataset, collection, judgments);
            var incoming = StrictJson.lines(LocalFiles.read(LocalFiles.path(options.value("reviews")), StrictJson.FILE_LIMIT).bytes(),
                    ReviewModel.Review.class, 10_000);
            var merged = reviews.merge(dataset, collection, judgments, history, incoming);
            reviews.publish(dataset, collection, judgments, merged, new OutputPublisher().preflight(options.value("output")));
            return 0;
        }
        var reports = new Reports();
        var history = reviews.read(options.value("reviews"), dataset, collection, judgments);
        var report = reports.calculate(dataset, collection, judgments, history, config, reports.read(options.value("baseline")));
        return reports.publish(report, new OutputPublisher().preflight(options.value("output")));
    }
}

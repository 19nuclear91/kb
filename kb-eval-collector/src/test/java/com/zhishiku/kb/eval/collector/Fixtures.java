package com.zhishiku.kb.eval.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class Fixtures {
    static final String SECRET = "sensitive-token-body-query";
    static final Clock UTC = Clock.fixed(Instant.parse("2026-11-05T06:00:00Z"), ZoneOffset.UTC);

    private Fixtures() {
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static ObjectNode json(String value) {
        return (ObjectNode) JsonSupport.parse(bytes(value), "test", 0);
    }

    static ObjectNode config() {
        return json("""
                {"schemaVersion":1,"runId":"baseline","configurationId":"config-v1","endpointId":"demo",
                 "baseUrl":"https://example.invalid/kb","identities":{"staff":{"tokenEnv":"TOKEN","expectedUserId":7}}}
                """);
    }

    static String label(String id) {
        return "{\"caseId\":\"" + id + "\",\"tags\":[],\"expectedEmpty\":true,\"relevance\":[]}";
    }

    static String item(String id, String identity) {
        return "{\"caseId\":\"" + id + "\",\"identity\":\"" + identity + "\",\"request\":{\"query\":\"" + SECRET + "\"}}";
    }

    static void files(Path root, String... ids) throws IOException {
        Files.writeString(root.resolve("dataset.json"), """
                {"schemaVersion":1,"datasetId":"demo","datasetVersion":"v1","corpusSnapshotId":"snap","labelsFile":"labels.jsonl"}
                """);
        Files.write(root.resolve("config.json"), JsonSupport.encode(config()));
        StringBuilder labels = new StringBuilder();
        StringBuilder cases = new StringBuilder();
        for (String id : ids) {
            labels.append(label(id)).append("\r\n");
            cases.append(item(id, "staff")).append('\n');
        }
        Files.writeString(root.resolve("labels.jsonl"), labels);
        Files.writeString(root.resolve("cases.jsonl"), cases);
    }

    static CollectionCli.Options options(Path root) {
        return new CollectionCli.Options(root.resolve("dataset.json").toString(), root.resolve("cases.jsonl").toString(),
                root.resolve("config.json").toString(), root.resolve("output").toString());
    }

    static String[] args(Path root) {
        CollectionCli.Options options = options(root);
        return new String[]{"--dataset", options.dataset(), "--cases", options.cases(), "--config", options.config(),
                "--output", options.output()};
    }

    static Transport.Response response(int status, String body) {
        return response(status, body, Map.of("Content-Type", List.of("application/json")));
    }

    static Transport.Response response(int status, String body, Map<String, List<String>> headers) {
        return new Transport.Response(status, HttpHeaders.of(headers, (name, value) -> true), new ByteArrayInputStream(bytes(body)));
    }

    static Transport.Response me(long user) {
        return response(200, "{\"code\":200,\"data\":{\"user\":{\"userId\":" + user
                + ",\"enabled\":true,\"username\":\"" + SECRET + "\"},\"mustChangePassword\":false,\"roles\":[99]}}");
    }

    static Transport.Response empty() {
        return response(200, "{\"code\":200,\"data\":[]}");
    }

    @FunctionalInterface
    interface Step {
        Transport.Response send(Transport.Request request, Deadline deadline) throws IOException, InterruptedException;
    }

    static final class FakeTransport implements Transport {
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final Queue<Step> steps = new ArrayDeque<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maximum = new AtomicInteger();
        boolean closed;

        FakeTransport add(Response response) {
            steps.add((request, deadline) -> response);
            return this;
        }

        FakeTransport add(Step step) {
            steps.add(step);
            return this;
        }

        @Override
        public Response send(Request request, Deadline deadline) throws IOException, InterruptedException {
            requests.add(request);
            maximum.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Step step = steps.poll();
                if (step == null) {
                    throw new AssertionError("未预期的请求");
                }
                return step.send(request, deadline);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static Collector collector(FakeTransport transport, AtomicLong clock, OutputPublisher publisher) {
        return new Collector(name -> SECRET, config -> transport, clock::get, UTC, publisher);
    }

    static JsonNode read(Path file) throws IOException {
        return json(Files.readString(file));
    }
}

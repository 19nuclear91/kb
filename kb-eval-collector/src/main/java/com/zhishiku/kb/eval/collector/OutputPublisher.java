package com.zhishiku.kb.eval.collector;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 只清理本次创建的固定文件，不遍历或跟随外部目录。 */
class OutputPublisher {
    private static final String PREFIX = ".kb-eval-collector-";
    private static final List<String> FILES = List.of("run.json", "collection.json", "results.jsonl");

    Path preflight(String output) {
        Path target = JsonSupport.path(output, null, "output");
        try {
            if (target.getParent() == null) {
                throw failure();
            }
            requireAbsent(target);
            Files.createDirectories(target.getParent());
            Path parent = target.getParent().toRealPath();
            if (parent.toString().startsWith("\\\\") || !Files.isWritable(parent)) {
                throw failure();
            }
            target = parent.resolve(target.getFileName());
            requireAbsent(target);
            return target;
        } catch (IOException | SecurityException e) {
            throw failure();
        }
    }

    protected BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private void requireAbsent(Path target) throws IOException {
        try {
            attributes(target);
        } catch (NoSuchFileException e) {
            // 仅明确的不存在允许继续，不能把名称超限或权限错误当成新目录。
            return;
        }
        throw failure();
    }

    Session begin(Path target) {
        try {
            return new Session(target, Files.createTempDirectory(target.getParent(), PREFIX));
        } catch (IOException | SecurityException e) {
            throw failure();
        }
    }

    protected OutputStream open(Path file) throws IOException {
        return Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    protected void write(Path file, byte[] bytes) throws IOException {
        try (OutputStream output = open(file)) {
            output.write(bytes);
        }
    }

    protected void move(Path staging, Path target) throws IOException {
        // 不使用 ATOMIC_MOVE：其目标已存在时的覆盖行为由平台决定。
        Files.move(staging, target);
    }

    protected void cleanup(Path staging, Path parent) throws IOException {
        Path normalized = staging.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(parent) || !normalized.getFileName().toString().startsWith(PREFIX)
                || Files.isSymbolicLink(normalized) || !normalized.toRealPath().getParent().equals(parent)) {
            throw new IOException("Unsafe staging directory");
        }
        for (String name : FILES) {
            Files.deleteIfExists(normalized.resolve(name));
        }
        Files.delete(normalized);
    }

    final class Session implements AutoCloseable {
        private final Path target;
        private final Path staging;
        private final MessageDigest digest;
        private OutputStream results;
        private boolean published;
        private int count;

        Session(Path target, Path staging) {
            this.target = target;
            this.staging = staging;
            try {
                digest = MessageDigest.getInstance("SHA-256");
                results = new DigestOutputStream(open(staging.resolve("results.jsonl")), digest);
            } catch (IOException | NoSuchAlgorithmException | SecurityException e) {
                try {
                    cleanup(staging, target.getParent());
                } catch (IOException | SecurityException ignored) {
                    throw failure();
                }
                throw failure();
            }
        }

        void append(String caseId, Protocol.SearchResult result) {
            checkInterrupted();
            byte[] bytes = JsonSupport.encode(Map.of("caseId", caseId, "status", "OK", "hits", result.hits(),
                    "latencyMs", result.latencyMs()));
            try {
                results.write(bytes);
                results.write('\n');
                count++;
            } catch (IOException | SecurityException e) {
                throw failure();
            }
        }

        void finish(Inputs.Batch batch, Map<String, Object> metadata) {
            checkInterrupted();
            if (count != batch.cases().size()) {
                throw failure();
            }
            try {
                results.close();
                results = null;
                String resultsSha = HexFormat.of().formatHex(digest.digest());
                metadata.put("resultsSha256", resultsSha);
                write(staging.resolve("collection.json"), JsonSupport.encode(metadata));
                write(staging.resolve("run.json"), JsonSupport.encode(Map.of(
                        "schemaVersion", 1, "runId", batch.config().runId(), "datasetId", batch.datasetId(),
                        "datasetVersion", batch.datasetVersion(), "corpusSnapshotId", batch.corpusSnapshotId(),
                        "configurationId", batch.config().configurationId(), "sourceType", "RECORDED",
                        "labelsSha256", batch.labelsSha256(), "resultsFile", "results.jsonl")));
                checkInterrupted();
                requireAbsent(target);
                move(staging, target);
                published = true;
            } catch (IOException | SecurityException e) {
                throw failure();
            }
        }

        @Override
        public void close() {
            if (published) {
                return;
            }
            boolean failed = false;
            if (results != null) {
                try {
                    results.close();
                } catch (IOException e) {
                    failed = true;
                }
                results = null;
            }
            try {
                cleanup(staging, target.getParent());
            } catch (IOException | SecurityException e) {
                failed = true;
            }
            if (failed) {
                throw failure();
            }
        }
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CollectionException(CollectionException.Code.INTERRUPTED, "output", 0, "request");
        }
    }

    private static CollectionException failure() {
        return new CollectionException(CollectionException.Code.OUTPUT, "output", 0, "file");
    }
}

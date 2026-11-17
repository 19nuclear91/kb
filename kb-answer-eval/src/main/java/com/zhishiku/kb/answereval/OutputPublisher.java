package com.zhishiku.kb.answereval;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 暂存目录提交；只清理本次创建且身份未改变的文件，不递归删除目录。 */
public class OutputPublisher {
    public static final long RUN_LIMIT = 2L * 1024 * 1024 * 1024;
    public static final int CAPTURE_LIMIT = 8 * 1024 * 1024;
    private static final String PREFIX = ".kb-answer-eval-";

    public record Target(Path path, Object parentKey) {
    }

    public Target preflight(String output) {
        Path target = LocalFiles.path(output);
        try {
            if (target.getParent() == null) {
                throw failure();
            }
            absent(target);
            Files.createDirectories(target.getParent());
            LocalFiles.checkAncestors(target);
            BasicFileAttributes parent = attributes(target.getParent());
            if (!parent.isDirectory() || !Files.isWritable(target.getParent())) {
                throw failure();
            }
            return new Target(target, identity(parent));
        } catch (IOException | SecurityException failure) {
            throw failure();
        }
    }

    public Session begin(Target target) {
        try {
            checkParent(target);
            absent(target.path());
            Path staging = Files.createTempDirectory(target.path().getParent(), PREFIX);
            return new Session(target, staging, identity(attributes(staging)));
        } catch (IOException | SecurityException failure) {
            throw failure();
        }
    }

    protected void write(OutputStream stream, byte[] bytes) throws IOException {
        stream.write(bytes);
    }

    protected void move(Path staging, Path target) throws IOException {
        // ATOMIC_MOVE 在目标已存在时可能覆盖；默认移动要求目标不存在。
        Files.move(staging, target);
    }

    private void checkParent(Target target) throws IOException {
        LocalFiles.checkAncestors(target.path());
        if (!Objects.equals(identity(attributes(target.path().getParent())), target.parentKey())) {
            throw failure();
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static Object identity(BasicFileAttributes attributes) {
        // Windows JDK 的 fileKey 可为空；创建时间辅助检测替换，路径边界仍独立逐次校验。
        return attributes.fileKey() == null ? attributes.creationTime() : attributes.fileKey();
    }

    private static void absent(Path path) throws IOException {
        try {
            attributes(path);
        } catch (NoSuchFileException absent) {
            return;
        }
        throw failure();
    }

    public final class Session implements AutoCloseable {
        private final Target target;
        private final Path staging;
        private final Object stagingKey;
        private final Map<Path, Object> created = new LinkedHashMap<>();
        private final Map<String, DataModel.FileRef> files = new LinkedHashMap<>();
        private long written;
        private boolean finished;
        private boolean failed;

        private Session(Target target, Path staging, Object stagingKey) {
            this.target = target;
            this.staging = staging;
            this.stagingKey = stagingKey;
        }

        public DataModel.FileRef put(String name, byte[] bytes) {
            if (finished || failed || "complete.json".equals(name) || files.containsKey(name)) {
                throw failure();
            }
            try {
                writeFile(name, bytes);
            } catch (RuntimeException failure) {
                // 写入可能已留下部分文件；会话失效后只允许按文件身份清理。
                failed = true;
                throw failure;
            }
            DataModel.FileRef reference = new DataModel.FileRef(name, StrictJson.sha(bytes));
            files.put(name, reference);
            return reference;
        }

        private void writeFile(String name, byte[] bytes) {
            if (name == null || name.isBlank() || name.length() > 255
                    || bytes.length > StrictJson.FILE_LIMIT || written + bytes.length > RUN_LIMIT) {
                throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
            }
            try {
                checkStaging();
                Path file = LocalFiles.child(staging, name);
                List<Path> parents = new ArrayList<>();
                for (Path parent = file.getParent(); !parent.equals(staging) && !created.containsKey(parent);
                     parent = parent.getParent()) {
                    parents.add(parent);
                }
                for (Path parent : parents.reversed()) {
                    Files.createDirectory(parent);
                    created.put(parent, identity(attributes(parent)));
                }
                checkStaging();
                try (OutputStream stream = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    created.put(file, identity(attributes(file)));
                    written += bytes.length;
                    write(stream, bytes);
                }
                checkStaging();
            } catch (IOException | SecurityException failure) {
                throw failure();
            }
        }

        public void finish() {
            if (finished || failed || files.isEmpty()) {
                throw failure();
            }
            try {
                checkStaging();
                for (DataModel.FileRef file : files.values()) {
                    LocalFiles.read(staging, file, StrictJson.FILE_LIMIT);
                }
                byte[] completion = StrictJson.bytes(Map.of("schemaVersion", 1, "files", files));
                if (completion.length > StrictJson.MANIFEST_LIMIT) {
                    throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
                }
                writeFile("complete.json", completion);
                checkStaging();
                absent(target.path());
                move(staging, target.path());
                finished = true;
            } catch (IOException | SecurityException failure) {
                failed = true;
                throw failure();
            } catch (RuntimeException failure) {
                failed = true;
                throw failure;
            }
        }

        private void checkStaging() throws IOException {
            LocalFiles.checkInterrupted();
            checkParent(target);
            LocalFiles.checkAncestors(staging);
            if (!Objects.equals(identity(attributes(staging)), stagingKey)) {
                throw failure();
            }
            for (Map.Entry<Path, Object> entry : created.entrySet()) {
                LocalFiles.checkAncestors(entry.getKey());
                if (!Objects.equals(identity(attributes(entry.getKey())), entry.getValue())) {
                    throw failure();
                }
            }
        }

        @Override
        public void close() {
            if (finished) {
                return;
            }
            try {
                // 取消后仍只做本次固定目标清理，不因线程中断跳过。
                checkParent(target);
                LocalFiles.checkAncestors(staging);
                if (!staging.getParent().equals(target.path().getParent())
                        || !staging.getFileName().toString().startsWith(PREFIX)
                        || !Objects.equals(identity(attributes(staging)), stagingKey)) {
                    throw failure();
                }
                List<Path> paths = new ArrayList<>(created.keySet()).reversed();
                for (Path path : paths) {
                    LocalFiles.checkAncestors(path);
                    if (!path.startsWith(staging) || !Objects.equals(identity(attributes(path)), created.get(path))) {
                        throw failure();
                    }
                    Files.delete(path);
                }
                Files.delete(staging);
            } catch (IOException | SecurityException failure) {
                throw failure();
            }
        }
    }

    private static EvaluationFailure failure() {
        return new EvaluationFailure(EvaluationFailure.Code.OUTPUT);
    }
}

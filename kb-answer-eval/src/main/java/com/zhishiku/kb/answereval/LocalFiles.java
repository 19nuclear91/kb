package com.zhishiku.kb.answereval;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 本地路径和冻结字节；不接受共享路径或通过链接跳转到其他目录。 */
public final class LocalFiles {
    private LocalFiles() {
    }

    public record Frozen(byte[] bytes, String sha256) {
        public Frozen {
            bytes = bytes.clone();
            Checks.sha(sha256);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String toString() {
            return "Frozen[sha256=" + sha256 + "]";
        }
    }

    public static Path path(String value) {
        try {
            Checks.text(value, 4096);
            String portable = value.replace((char) 92, '/');
            if (portable.startsWith("//") || portable.indexOf('*') >= 0 || portable.indexOf('?') >= 0
                    || (portable.contains(":") && !portable.matches("^[A-Za-z]:/[^:]*$"))) {
                throw failure();
            }
            Path path = Path.of(value).toAbsolutePath().normalize();
            checkAncestors(path);
            return path;
        } catch (InvalidPathException | SecurityException failure) {
            throw failure();
        }
    }

    public static Path child(Path root, String relative) {
        try {
            Checks.text(relative, 255);
            Path name = Path.of(relative);
            if (name.isAbsolute() || relative.indexOf(':') >= 0 || relative.indexOf('*') >= 0
                    || relative.indexOf('?') >= 0 || relative.startsWith("/") || relative.charAt(0) == 92) {
                throw failure();
            }
            Path child = root.resolve(name).normalize();
            if (child.equals(root) || !child.startsWith(root)) {
                throw failure();
            }
            checkAncestors(child);
            return child;
        } catch (InvalidPathException | SecurityException failure) {
            throw failure();
        }
    }

    public static void checkAncestors(Path path) {
        try {
            for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                        && (Files.isSymbolicLink(current) || !current.toRealPath().equals(current))) {
                    throw failure();
                }
            }
        } catch (IOException | SecurityException failure) {
            throw failure();
        }
    }

    public static Frozen read(Path path, int limit) {
        checkAncestors(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure();
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = StrictJson.bounded(input, limit, LocalFiles::checkInterrupted);
            checkAncestors(path);
            return new Frozen(bytes, StrictJson.sha(bytes));
        } catch (IOException | SecurityException failure) {
            throw failure();
        }
    }

    public static Frozen read(Path root, DataModel.FileRef reference, int limit) {
        Frozen file = read(child(root, reference.file()), limit);
        if (!file.sha256().equals(reference.sha256())) {
            throw new EvaluationFailure(EvaluationFailure.Code.DIGEST);
        }
        return file;
    }

    public static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new EvaluationFailure(EvaluationFailure.Code.CANCELLED);
        }
    }

    private static EvaluationFailure failure() {
        return new EvaluationFailure(EvaluationFailure.Code.PATH);
    }
}

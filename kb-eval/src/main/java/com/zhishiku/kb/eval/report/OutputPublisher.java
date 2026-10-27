package com.zhishiku.kb.eval.report;

import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.input.EvaluationException.Code;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 在同一父目录暂存完整产物，失败只清理本次暂存目录。
 */
public final class OutputPublisher {
    @FunctionalInterface
    public interface DirectoryWriter {
        void write(Path staging) throws IOException;
    }

    public static Path outputPath(String value) {
        if (value == null || value.isBlank() || value.startsWith("\\\\") || value.startsWith("//")
                || (value.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*") && !value.matches("^[A-Za-z]:[\\\\/].*"))) {
            throw failure(Code.LOCAL_FILE_REQUIRED);
        }
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (path.toString().startsWith("\\\\")) {
                throw failure(Code.LOCAL_FILE_REQUIRED);
            }
            return path;
        } catch (InvalidPathException | SecurityException e) {
            throw failure(Code.OUTPUT_FAILED);
        }
    }

    public void publish(Path target, DirectoryWriter writer) {
        Path output = target.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent == null) {
            throw failure(Code.OUTPUT_FAILED);
        }
        Path staging = null;
        try {
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(Code.OUTPUT_EXISTS);
            }
            Files.createDirectories(parent);
            staging = Files.createTempDirectory(parent, ".kb-eval-");
            writer.write(staging);
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(Code.OUTPUT_EXISTS);
            }
            // 同父目录的目录移动，不使用允许隐式替换目标的 ATOMIC_MOVE。
            Files.move(staging, output);
            staging = null;
        } catch (EvaluationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw failure(Code.OUTPUT_FAILED);
        } finally {
            if (staging != null) {
                cleanup(staging, parent);
            }
        }
    }

    private static void cleanup(Path staging, Path parent) {
        Path absolute = staging.toAbsolutePath().normalize();
        Path expectedParent = parent.toAbsolutePath().normalize();
        if (!absolute.getParent().equals(expectedParent) || !absolute.getFileName().toString().startsWith(".kb-eval-")) {
            throw failure(Code.OUTPUT_FAILED);
        }
        try (var paths = Files.walk(absolute)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(absolute)) {
                    throw failure(Code.OUTPUT_FAILED);
                }
                Files.delete(path);
            }
        } catch (IOException | SecurityException e) {
            throw failure(Code.OUTPUT_FAILED);
        }
    }

    private static EvaluationException failure(Code code) {
        return new EvaluationException(code, "output", 0, "output");
    }
}

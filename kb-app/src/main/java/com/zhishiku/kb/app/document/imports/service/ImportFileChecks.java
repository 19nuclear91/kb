package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.document.imports.model.ImportModels.FileIntent;
import com.zhishiku.kb.app.document.imports.support.ImportInput;
import com.zhishiku.kb.common.file.DocumentFilePolicy;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 匹配核对与内容检查分开，内容拒绝可归入已登记尝试的确定失败。 */
public final class ImportFileChecks {
    private ImportFileChecks() {
    }

    public static void match(MultipartFile file, FileIntent expected, long maxBytes) {
        if (file == null || file.isEmpty()) {
            throw new ImportException(ImportException.Reason.INVALID_FILE);
        }
        if (file.getSize() > maxBytes) {
            throw new ImportException(ImportException.Reason.PAYLOAD_TOO_LARGE);
        }
        if (file.getSize() != expected.sizeBytes()
                || !ImportInput.fileName(file.getOriginalFilename()).equals(expected.fileName())) {
            throw new ImportException(ImportException.Reason.CONFLICT);
        }
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long count = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ImportException(ImportException.Reason.UNAVAILABLE);
                }
                if (read == 0) {
                    int single = input.read();
                    if (single < 0) {
                        break;
                    }
                    buffer[0] = (byte) single;
                    read = 1;
                }
                count = Math.addExact(count, read);
                if (count > maxBytes) {
                    throw new ImportException(ImportException.Reason.PAYLOAD_TOO_LARGE);
                }
                if (count > expected.sizeBytes()) {
                    throw new ImportException(ImportException.Reason.CONFLICT);
                }
                digest.update(buffer, 0, read);
            }
            if (count != expected.sizeBytes() || !HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
                throw new ImportException(ImportException.Reason.CONFLICT);
            }
        } catch (IOException | NoSuchAlgorithmException | ArithmeticException ignored) {
            throw new ImportException(ImportException.Reason.UNAVAILABLE);
        }
    }

    public static String inspect(MultipartFile file, FileIntent expected, long maxBytes) {
        if (Thread.currentThread().isInterrupted()) {
            throw new ImportException(ImportException.Reason.UNAVAILABLE);
        }
        try (InputStream input = file.getInputStream()) {
            return DocumentFilePolicy.inspect(input, expected.fileName(), maxBytes);
        } catch (InterruptedIOException ignored) {
            Thread.currentThread().interrupt();
            throw new ImportException(ImportException.Reason.UNAVAILABLE);
        } catch (IOException | IllegalArgumentException ignored) {
            throw new ImportException(ImportException.Reason.INVALID_FILE);
        }
    }
}

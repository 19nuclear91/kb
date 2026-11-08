package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.document.imports.ImportProperties;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FailureCode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.support.ImportDeadline;
import com.zhishiku.kb.app.storage.StorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/** 同步请求协调器，存储退出后才开始最终事务；未知提交绝不进入失败补偿。 */
@Service
public class ImportUploadService {
    private final ImportAttemptService attempts;
    private final ImportAcceptanceService acceptance;
    private final ImportFailureService failures;
    private final ImportObjectWriter objects;
    private final ImportProperties properties;
    private final StorageProperties storage;

    public ImportUploadService(ImportAttemptService attempts, ImportAcceptanceService acceptance,
                               ImportFailureService failures, ImportObjectWriter objects,
                               ImportProperties properties, StorageProperties storage) {
        this.attempts = attempts;
        this.acceptance = acceptance;
        this.failures = failures;
        this.objects = objects;
        this.properties = properties;
        this.storage = storage;
    }

    public Receipt upload(String token, long importId, long itemId, String requestId, long revision, MultipartFile file) {
        var intent = attempts.preflight(token, importId, itemId);
        ImportFileChecks.match(file, intent.file(), storage.maxUploadBytes());
        var deadline = new ImportDeadline(Duration.ofSeconds(properties.attemptSeconds()));
        var started = attempts.begin(token, importId, itemId, requestId, revision);
        if (started.receipt() != null) {
            ImportFileChecks.inspect(file, intent.file(), storage.maxUploadBytes());
            return started.receipt();
        }
        String mime;
        try {
            deadline.remaining();
            mime = ImportFileChecks.inspect(file, intent.file(), storage.maxUploadBytes());
            deadline.remaining();
            objects.put(started.attempt(), started.item(), file, mime, deadline);
        } catch (RuntimeException failed) {
            ImportException error = failed instanceof ImportException known ? known
                    : new ImportException(ImportException.Reason.UNAVAILABLE);
            FailureCode code = switch (error.reason()) {
                case INVALID_FILE -> FailureCode.INVALID_FILE;
                case PAYLOAD_TOO_LARGE -> FailureCode.PAYLOAD_TOO_LARGE;
                default -> FailureCode.STORAGE_UNAVAILABLE;
            };
            try {
                failures.invalidate(started.attempt(), code);
            } catch (RuntimeException unknown) {
                throw new ImportException(ImportException.Reason.UNAVAILABLE);
            }
            throw error;
        }
        // 从这里开始，异常可能来自已成功的提交；只读核对或到期恢复负责收敛。
        return acceptance.accept(token, importId, started.attempt(), mime);
    }
}

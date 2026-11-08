package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.document.imports.ImportProperties;
import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.AttemptState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FailureCode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Attempt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Batch;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.document.imports.support.ImportInput;
import com.zhishiku.kb.app.storage.ObjectKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 项执行资格的事务边界；本服务不执行对象网络操作。 */
@Service
public class ImportAttemptService {
    private final ImportMapper mapper;
    private final ImportAccess access;
    private final ImportProperties properties;
    private final TransactionTemplate transaction;

    public ImportAttemptService(ImportMapper mapper, ImportAccess access, ImportProperties properties,
                                PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.access = access;
        this.properties = properties;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    /** 读取原文件描述供事务外匹配，只有本人且当前有 edit 才可取得。 */
    public Item preflight(String token, long importId, long itemId) {
        var actor = access.current(token);
        Batch batch = owned(actor.userId(), importId);
        access.requireUpload(token, batch.spaceId());
        return requireItem(mapper.item(importId, itemId), importId);
    }

    public Started begin(String token, long importId, long itemId, String rawRequestId, long expectedRevision) {
        String requestId = ImportInput.requestId(rawRequestId);
        if (expectedRevision < 0) {
            throw ImportInput.invalid();
        }
        return transaction.execute(status -> {
            var actor = access.current(token);
            Batch batch = owned(actor.userId(), importId);
            var verified = access.requireUpload(token, batch.spaceId());
            if (verified.userId() != actor.userId() || verified.authEpoch() != actor.authEpoch()) {
                throw new ImportException(ImportException.Reason.FORBIDDEN);
            }
            Item item = requireItem(mapper.lockItem(itemId), importId);
            String binding = ImportInput.digest(List.of(actor.userId(), importId, itemId, expectedRevision, item.file()));
            Attempt previous = mapper.attemptByRequest(itemId, requestId);
            if (previous != null && !previous.bindingDigest().equals(binding)) {
                throw conflict();
            }
            if (item.state() == ItemState.ACCEPTED) {
                return new Started(batch, item, null, item.receipt());
            }
            if (previous != null || item.state() == ItemState.PROCESSING || item.revision() != expectedRevision
                    || item.failureCode() == FailureCode.INVALID_FILE || item.failureCode() == FailureCode.PAYLOAD_TOO_LARGE) {
                throw conflict();
            }
            long generation;
            try {
                generation = Math.addExact(expectedRevision, 1);
            } catch (ArithmeticException ignored) {
                throw conflict();
            }
            var now = mapper.now();
            Attempt attempt = new Attempt(mapper.nextAttemptId(), itemId, requestId, binding, generation,
                    objectKey(item.fileName()), AttemptState.ACTIVE, actor.authEpoch(), now,
                    now.plusSeconds(properties.attemptSeconds()), null);
            ImportCreateService.requireOne(mapper.insertAttempt(attempt));
            ImportCreateService.requireOne(mapper.claim(itemId, expectedRevision, attempt.id()));
            return new Started(batch, item, attempt, null);
        });
    }

    private Batch owned(long owner, long importId) {
        Batch batch = mapper.batch(owner, importId);
        if (batch == null) {
            throw new ImportException(ImportException.Reason.NOT_FOUND);
        }
        return batch;
    }

    private Item requireItem(Item item, long importId) {
        if (item == null || item.importId() != importId) {
            throw new ImportException(ImportException.Reason.NOT_FOUND);
        }
        return item;
    }

    private String objectKey(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot > 0 ? fileName.substring(dot).toLowerCase(Locale.ROOT) : "";
        if (!extension.matches("\\.[a-z0-9]{1,10}")) {
            extension = "";
        }
        return ObjectKeys.DOCUMENTS_PREFIX + "imports/" + UUID.randomUUID() + extension;
    }

    private ImportException conflict() {
        return new ImportException(ImportException.Reason.CONFLICT);
    }

    /**
     * 本次合法执行或固定重放结果。
     * @param batch 固定批次
     * @param item 固定项意图
     * @param attempt 新执行；重放时为空
     * @param receipt 已受理回执；新执行时为空
     */
    public record Started(Batch batch, Item item, Attempt attempt, Receipt receipt) {
    }
}

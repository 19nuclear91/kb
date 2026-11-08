package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.AttemptState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Attempt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.document.service.DocumentWriteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 文档写服务以 REQUIRED 加入此事务；所有回执与尝试变更共用提交边界。 */
@Service
public class ImportAcceptanceService {
    private final ImportMapper mapper;
    private final ImportAccess access;
    private final DocumentWriteService documents;
    private final TransactionTemplate transaction;

    public ImportAcceptanceService(ImportMapper mapper, ImportAccess access, DocumentWriteService documents,
                                   PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.access = access;
        this.documents = documents;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    public Receipt accept(String token, long importId, Attempt execution, String mime) {
        return transaction.execute(status -> {
            Item item = mapper.lockItem(execution.itemId());
            var actor = access.current(token);
            var batch = mapper.batch(actor.userId(), importId);
            if (batch == null || item == null || item.importId() != importId) {
                throw new ImportException(ImportException.Reason.NOT_FOUND);
            }
            if (mapper.shareSpace(batch.spaceId()) == null) {
                throw new ImportException(ImportException.Reason.FORBIDDEN);
            }
            var verified = access.requireUpload(token, batch.spaceId());
            if (verified.userId() != batch.ownerUserId() || verified.authEpoch() != execution.authEpoch()) {
                throw new ImportException(ImportException.Reason.FORBIDDEN);
            }
            if (item.state() == ItemState.ACCEPTED) {
                return item.receipt();
            }
            Attempt current = mapper.attempt(execution.id());
            if (!owns(item, current) || !current.equals(execution) || !mapper.now().isBefore(current.leaseUntil())
                    || Thread.currentThread().isInterrupted()) {
                throw new ImportException(ImportException.Reason.CONFLICT);
            }
            var created = documents.create(batch.ownerUserId(), batch.spaceId(), item.title(), batch.securityLevel(),
                    current.objectKey(), item.fileName(), item.sizeBytes(), item.sha256(), mime);
            Receipt receipt = new Receipt(created.documentId(), created.versionId(), created.taskId());
            ImportCreateService.requireOne(mapper.accept(item.id(), current.id(), current.generation(), receipt));
            ImportCreateService.requireOne(mapper.finish(current.id(), AttemptState.ACCEPTED.name()));
            return receipt;
        });
    }

    static boolean owns(Item item, Attempt attempt) {
        return item != null && attempt != null && item.state() == ItemState.PROCESSING
                && item.attemptId() != null && item.attemptId() == attempt.id()
                && item.id() == attempt.itemId() && item.revision() == attempt.generation()
                && attempt.state() == AttemptState.ACTIVE;
    }
}

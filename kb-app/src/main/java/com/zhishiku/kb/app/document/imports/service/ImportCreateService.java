package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Created;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Manifest;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Resolution;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Batch;
import com.zhishiku.kb.app.document.imports.support.ImportInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 创建请求幂等；ON CONFLICT 等待竞争提交后，在 READ COMMITTED 下读取原绑定。 */
@Service
public class ImportCreateService {
    private final ImportMapper mapper;
    private final ImportAccess access;
    private final SecurityLevelDictionary levels;
    private final TransactionTemplate transaction;

    public ImportCreateService(ImportMapper mapper, ImportAccess access, SecurityLevelDictionary levels,
                               PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.access = access;
        this.levels = levels;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    public Created create(String token, String rawRequestId, Manifest manifest) {
        String requestId = ImportInput.requestId(rawRequestId);
        return transaction.execute(status -> {
            var actor = access.requireUpload(token, manifest.spaceId());
            if (mapper.shareSpace(manifest.spaceId()) == null) {
                throw new ImportException(ImportException.Reason.FORBIDDEN);
            }
            if (!levels.contains(manifest.securityLevel())) {
                throw ImportInput.invalid();
            }
            Batch proposed = new Batch(mapper.nextBatchId(), actor.userId(), requestId, manifest.digest(),
                    manifest.spaceId(), manifest.securityLevel(), manifest.items().size(), manifest.totalBytes(), null);
            int inserted = mapper.insertBatch(proposed);
            if (inserted == 0) {
                Batch previous = mapper.byRequest(actor.userId(), requestId);
                if (previous == null) {
                    throw new ImportException(ImportException.Reason.UNAVAILABLE);
                }
                if (!previous.manifestDigest().equals(manifest.digest())) {
                    throw new ImportException(ImportException.Reason.CONFLICT);
                }
                access.requireUpload(token, manifest.spaceId());
                return new Created(previous.id(), true);
            }
            requireOne(inserted);
            int ordinal = 0;
            for (var file : manifest.items()) {
                requireOne(mapper.insertItem(proposed.id(), ++ordinal, file));
            }
            var finalActor = access.requireUpload(token, manifest.spaceId());
            if (actor.userId() != finalActor.userId() || actor.authEpoch() != finalActor.authEpoch()) {
                throw new ImportException(ImportException.Reason.FORBIDDEN);
            }
            return new Created(proposed.id(), false);
        });
    }

    public Resolution resolve(String token, String rawRequestId) {
        String requestId = ImportInput.requestId(rawRequestId);
        var actor = access.current(token);
        var batch = mapper.byRequest(actor.userId(), requestId);
        return new Resolution(batch != null, batch == null ? null : batch.id());
    }

    static void requireOne(int rows) {
        if (rows != 1) {
            throw new ImportException(ImportException.Reason.UNAVAILABLE);
        }
    }
}

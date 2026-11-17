package com.zhishiku.kb.app.document.diff.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.diff.DocumentDiffProperties;
import com.zhishiku.kb.app.document.diff.mapper.DocumentDiffMapper;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Response;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Version;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Warning;
import com.zhishiku.kb.app.document.diff.model.SnapshotBlock;
import com.zhishiku.kb.app.document.diff.model.SnapshotHeader;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class DocumentDiffService {
    private final DocumentVersionAccessService access;
    private final PermissionService permissions;
    private final DocumentDiffMapper mapper;
    private final DocumentDiffProperties properties;
    private final DiffAdmission admission;
    private final DiffExecutor executor;
    private final TransactionTemplate reads;

    public DocumentDiffService(DocumentVersionAccessService access, PermissionService permissions,
                               DocumentDiffMapper mapper, DocumentDiffProperties properties, DiffAdmission admission,
                               DiffExecutor executor, PlatformTransactionManager transactions) {
        this.access = access;
        this.permissions = permissions;
        this.mapper = mapper;
        this.properties = properties;
        this.admission = admission;
        this.executor = executor;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Response compare(long actor, long documentId, long baseVersionId, long targetVersionId) {
        if (documentId <= 0 || baseVersionId <= 0 || targetVersionId <= 0 || baseVersionId == targetVersionId) {
            throw new DiffException(400, "INVALID_INPUT");
        }
        try {
            var keys = Set.of(new SourceKey(documentId, baseVersionId), new SourceKey(documentId, targetVersionId));
            if (!access.visible(permissions.principalOf(actor), keys, VersionAccessPurpose.INTERACTIVE).containsAll(keys)) {
                throw new DiffException(404, "NOT_FOUND");
            }
        } catch (DiffException expected) {
            throw expected;
        } catch (Exception failure) {
            throw new DiffException(503, "UNAVAILABLE");
        }
        var ticket = admission.acquire();
        Loaded loaded;
        try {
            loaded = reads.execute(status -> load(documentId, baseVersionId, targetVersionId));
            if (loaded == null) {
                throw ApiException.unavailable("UNAVAILABLE");
            }
        } catch (Exception failure) {
            ticket.close();
            throw new DiffException(503, "UNAVAILABLE");
        }
        if (loaded.status != null) {
            ticket.close();
            return response(documentId, loaded, loaded.status);
        }
        try {
            return executor.execute(ticket, deadline -> {
                var computed = new BlockDiff(properties).compare(loaded.baseBlocks, loaded.targetBlocks, deadline);
                if (computed.similarity() < properties.minSimilarity()) {
                    return response(documentId, loaded, "LOW_SIMILARITY");
                }
                var values = new ValueChangeExtractor().extract(computed.operations(), deadline);
                return new Response(documentId, version(loaded.base), version(loaded.target), "AVAILABLE", List.of(),
                        loaded.warnings, computed.stats(), computed.operations(), values);
            });
        } catch (DiffDeadline.Expired timeout) {
            return response(documentId, loaded, "TIMEOUT");
        }
    }

    private Loaded load(long documentId, long baseId, long targetId) {
        var headers = mapper.headers(documentId, baseId, targetId);
        if (headers == null || headers.size() != 2) {
            throw ApiException.unavailable("UNAVAILABLE");
        }
        var base = headers.stream().filter(header -> header.versionId() == baseId).findFirst().orElseThrow();
        var target = headers.stream().filter(header -> header.versionId() == targetId).findFirst().orElseThrow();
        List<String> missing = new ArrayList<>();
        if (!base.available()) {
            missing.add("BASE");
        }
        if (!target.available()) {
            missing.add("TARGET");
        }
        List<Warning> warnings = new ArrayList<>();
        if (base.parserName() != null && target.parserName() != null
                && (!Objects.equals(base.parserName(), target.parserName())
                || !Objects.equals(base.parserVersion(), target.parserVersion()))) {
            warnings.add(new Warning("PARSER_DIFFERS", null));
        }
        if (base.truncated()) {
            warnings.add(new Warning("TRUNCATED", "BASE"));
        }
        if (target.truncated()) {
            warnings.add(new Warning("TRUNCATED", "TARGET"));
        }
        String status = !missing.isEmpty() ? "SNAPSHOT_MISSING" : oversized(base) || oversized(target) ? "TOO_LARGE" : null;
        var baseBlocks = status == null ? mapper.blocks(baseId) : List.<SnapshotBlock>of();
        var targetBlocks = status == null ? mapper.blocks(targetId) : List.<SnapshotBlock>of();
        if (status == null) {
            validate(base, baseBlocks);
            validate(target, targetBlocks);
        }
        return new Loaded(base, target, List.copyOf(missing), List.copyOf(warnings), status, baseBlocks, targetBlocks);
    }

    private static void validate(SnapshotHeader header, List<SnapshotBlock> blocks) {
        if (blocks == null || blocks.size() != header.blockCount()
                || blocks.stream().mapToLong(block -> block.text().codePointCount(0, block.text().length())).sum()
                != header.charCount()) {
            throw ApiException.unavailable("UNAVAILABLE");
        }
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).index() != i) {
                throw ApiException.unavailable("UNAVAILABLE");
            }
        }
    }

    private boolean oversized(SnapshotHeader header) {
        return header.blockCount() > properties.maxBlocksPerVersion() || header.charCount() > properties.maxCharsPerVersion();
    }

    private static Version version(SnapshotHeader header) {
        return new Version(header.versionId(), header.versionNo(), header.parserName(), header.parserVersion(), header.truncated());
    }

    private static Response response(long documentId, Loaded loaded, String status) {
        return new Response(documentId, version(loaded.base), version(loaded.target), status, loaded.missing,
                loaded.warnings, null, List.of(), List.of());
    }

    private record Loaded(SnapshotHeader base, SnapshotHeader target, List<String> missing, List<Warning> warnings,
                          String status, List<SnapshotBlock> baseBlocks, List<SnapshotBlock> targetBlocks) {
    }
}

package com.zhishiku.kb.app.document.suggestion;

import com.zhishiku.kb.app.document.diff.mapper.DocumentDiffMapper;
import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.model.CategoryRow;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.common.suggestion.SuggestionData;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Service
public class SuggestionMaterials {
    public static final class Rejection extends RuntimeException {
        public Rejection(String reason) {
            super(reason);
        }
    }

    private final SuggestionAccess access;
    private final DocumentDiffMapper snapshots;
    private final CategoryMapper categories;
    private final SuggestionStore store;

    public SuggestionMaterials(SuggestionAccess access, DocumentDiffMapper snapshots, CategoryMapper categories, SuggestionStore store) {
        this.store = store;
        this.access = access;
        this.snapshots = snapshots;
        this.categories = categories;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public SuggestionData.Material load(String token, SuggestionJob job) {
        if (job.deadline().toInstant().toEpochMilli() <= System.currentTimeMillis()) {
            throw new Rejection("TIMEOUT");
        }
        authorize(token, job);
        var headers = snapshots.headers(job.docId(), job.sourceVersionId(), job.sourceVersionId());
        if (headers.size() != 1 || !headers.getFirst().available()) {
            throw new Rejection("SNAPSHOT_MISSING");
        }
        var header = headers.getFirst();
        if (header.truncated()) {
            throw new Rejection("SNAPSHOT_TRUNCATED");
        }
        if (header.blockCount() == 0 || header.charCount() == 0) {
            throw new Rejection("SNAPSHOT_EMPTY");
        }
        if (header.blockCount() > 4096 || header.charCount() > 250000
                || snapshots.materialBytes(job.sourceVersionId()) > SuggestionProtocol.MATERIAL_LIMIT) {
            throw new Rejection("INPUT_TOO_LARGE");
        }
        var rows = snapshots.blocks(job.sourceVersionId());
        if (rows.size() != header.blockCount()) {
            throw new Rejection("UNAVAILABLE");
        }
        var blocks = new ArrayList<SuggestionData.Block>();
        long chars = 0;
        for (var row : rows) {
            if (row.index() != blocks.size() || row.text() == null || row.text().isBlank()) {
                throw new Rejection("UNAVAILABLE");
            }
            chars += row.text().codePointCount(0, row.text().length());
            blocks.add(new SuggestionData.Block(row.index(), row.kind(), row.text(), row.headingPath(),
                    row.pageNo(), row.tableCellsJson()));
        }
        if (chars != header.charCount()) {
            throw new Rejection("UNAVAILABLE");
        }
        var result = new SuggestionData.Material(blocks, directory(job.spaceId(), job.treeVersion()));
        if (!store.bindSnapshot(job.requestId())) {
            throw new Rejection("SNAPSHOT_MISSING");
        }
        return result;
    }

    public void authorizePermit(String token, SuggestionJob job) {
        access.lockForPermit(job);
        authorize(token, job);
        var headers = snapshots.headers(job.docId(), job.sourceVersionId(), job.sourceVersionId());
        if (headers.size() != 1 || !headers.getFirst().available() || !store.snapshotMatches(job.requestId())) {
            throw new Rejection("SNAPSHOT_MISSING");
        }
        if (headers.getFirst().truncated()) {
            throw new Rejection("SNAPSHOT_TRUNCATED");
        }
    }

    public void authorize(String token, SuggestionJob job) {
        var current = access.current(token, job.docId());
        if (current.actorId() != job.actorId() || current.authEpoch() != job.authEpoch()
                || current.departmentId() != job.departmentId()) {
            throw new MetadataException(MetadataException.Reason.FORBIDDEN);
        }
        String conflict = job.conflict(current);
        if (conflict != null) {
            throw new Rejection(conflict);
        }
        access.requireModelSource(job);
    }

    private record Branch(Long parent, List<String> path) {
    }

    private List<SuggestionData.Category> directory(long space, long version) {
        var branches = new ArrayDeque<Branch>();
        branches.add(new Branch(null, List.of()));
        var seen = new HashSet<Long>();
        var result = new ArrayList<SuggestionData.Category>();
        while (!branches.isEmpty()) {
            Branch branch = branches.removeFirst();
            CategoryRow last = null;
            List<CategoryRow> page;
            do {
                if (!Long.valueOf(version).equals(categories.version(space))) {
                    throw new Rejection("TREE_VERSION_CONFLICT");
                }
                page = categories.children(space, branch.parent(), last == null ? null : last.sortOrder(),
                        last == null ? null : last.id(), 64);
                for (var row : page) {
                    if (result.size() >= 256) {
                        throw new Rejection("INPUT_TOO_LARGE");
                    }
                    if (row.spaceId() != space || !seen.add(row.id()) || branch.path().size() >= 8) {
                        throw new Rejection("UNAVAILABLE");
                    }
                    var path = new ArrayList<>(branch.path());
                    path.add(row.name());
                    result.add(new SuggestionData.Category(row.id(), path));
                    branches.add(new Branch(row.id(), path));
                    last = row;
                }
            } while (page.size() == 64);
        }
        return result;
    }
}

package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.BatchView;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FailureCode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemView;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Page;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Batch;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.document.imports.support.ImportCursor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 当前资格控制清单投影，固定受理历史不查询文档正文。 */
@Service
public class ImportReadService {
    private final ImportMapper mapper;
    private final ImportAccess access;

    public ImportReadService(ImportMapper mapper, ImportAccess access) {
        this.mapper = mapper;
        this.access = access;
    }

    public Page<BatchView> list(String token, int limit, String cursor) {
        ImportCursor.limit(limit);
        long owner = access.current(token).userId();
        Long before = ImportCursor.decode(cursor, owner, 0);
        List<Batch> rows = mapper.batches(owner, before, limit + 1);
        boolean more = rows.size() > limit;
        List<BatchView> views = new ArrayList<>();
        for (Batch row : rows.subList(0, Math.min(rows.size(), limit))) {
            views.add(view(owner, row));
        }
        String next = more ? ImportCursor.encode(owner, 0, views.getLast().importId()) : null;
        return new Page<>(views, more, next);
    }

    public BatchView detail(String token, long id) {
        long owner = access.current(token).userId();
        return view(owner, owned(owner, id));
    }

    public Page<ItemView> items(String token, long id, int limit, String cursor) {
        ImportCursor.limit(limit);
        long owner = access.current(token).userId();
        Batch batch = owned(owner, id);
        Long after = ImportCursor.decode(cursor, owner, id);
        boolean allowed = access.canUpload(owner, batch.spaceId());
        List<Item> rows = mapper.items(id, after == null ? 0 : after.intValue(), limit + 1);
        boolean more = rows.size() > limit;
        List<ItemView> views = rows.subList(0, Math.min(rows.size(), limit)).stream()
                .map(row -> itemView(row, allowed)).toList();
        String next = more ? ImportCursor.encode(owner, id, views.getLast().ordinal()) : null;
        return new Page<>(views, more, next);
    }

    public ItemView item(String token, long importId, long itemId) {
        long owner = access.current(token).userId();
        Batch batch = owned(owner, importId);
        Item item = mapper.item(importId, itemId);
        if (item == null) {
            throw new ImportException(ImportException.Reason.NOT_FOUND);
        }
        return itemView(item, access.canUpload(owner, batch.spaceId()));
    }

    private Batch owned(long owner, long id) {
        Batch batch = mapper.batch(owner, id);
        if (batch == null) {
            throw new ImportException(ImportException.Reason.NOT_FOUND);
        }
        return batch;
    }

    private BatchView view(long owner, Batch batch) {
        boolean allowed = access.canUpload(owner, batch.spaceId());
        var counts = mapper.counts(batch.id());
        if (counts == null || (long) counts.pending() + counts.processing() + counts.accepted() + counts.failed() != batch.itemCount()) {
            throw new ImportException(ImportException.Reason.UNAVAILABLE);
        }
        return new BatchView(batch.id(), batch.createdAt(), counts.state(), counts, allowed,
                allowed ? batch.spaceId() : null, allowed ? batch.securityLevel() : null);
    }

    private ItemView itemView(Item item, boolean allowed) {
        boolean retryable = item.state() == ItemState.PENDING || (item.state() == ItemState.FAILED
                && item.failureCode() != FailureCode.INVALID_FILE && item.failureCode() != FailureCode.PAYLOAD_TOO_LARGE);
        return new ItemView(item.id(), item.importId(), item.ordinal(), item.revision(), item.state(),
                item.failureCode(), allowed && retryable, allowed ? item.file() : null, item.receipt());
    }
}

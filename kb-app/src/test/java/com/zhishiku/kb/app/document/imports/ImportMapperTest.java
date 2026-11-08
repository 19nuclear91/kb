package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.document.imports.mapper.ImportMapper;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Counts;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FileIntent;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import com.zhishiku.kb.app.support.BoundValues;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用实际 MyBatis 参数及结果处理，不连接数据库。 */
class ImportMapperTest {
    private static final String NS = ImportMapper.class.getName() + ".";

    private Configuration configuration() {
        Configuration value = new Configuration();
        value.setMapUnderscoreToCamelCase(true);
        value.setArgNameBasedConstructorAutoMapping(true);
        value.addMapper(ImportMapper.class);
        return value;
    }

    @Test
    void dynamicPagingBindsOwnerAndOptionalCursor() {
        var config = configuration();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("owner", Long.MAX_VALUE);
        args.put("before", null);
        args.put("limit", 21);
        assertThat(BoundValues.values(config, NS + "batches", args)).containsExactly(Long.MAX_VALUE, 21);
        args.put("before", 88L);
        assertThat(BoundValues.values(config, NS + "batches", args)).containsExactly(Long.MAX_VALUE, 88L, 21);
    }

    @Test
    void writesBindTheOriginalItemAndExecutionIdentity() {
        var config = configuration();
        var file = new FileIntent("one", "原件.txt", "标题", 5, "a".repeat(64));
        assertThat(BoundValues.values(config, NS + "insertItem", Map.of("importId", 9L, "ordinal", 2, "file", file)))
                .containsExactly(9L, "one", 2, "原件.txt", "标题", 5L, "a".repeat(64));
        assertThat(BoundValues.values(config, NS + "accept", Map.of("id", 2L, "attemptId", 3L, "generation", 7L,
                "receipt", new Receipt(40, 50, 60)))).containsExactly(40L, 50L, 60L, 2L, 3L, 7L);
    }

    @Test
    void mapsPendingItemWithNullReceiptInsteadOfZeroIdentity() throws Exception {
        var row = item();
        Item result = MapperRowStub.readOne(configuration(), NS + "item", Map.of("importId", 9L, "id", 2L), row, Item.class);
        assertThat(result.id()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.state()).isEqualTo(ItemState.PENDING);
        assertThat(result.attemptId()).isNull();
        assertThat(result.receipt()).isNull();
        assertThat(result.file()).isEqualTo(new FileIntent("one", "原件.txt", "标题", 5L, "a".repeat(64)));
    }

    @Test
    void mapsAcceptedItemAndExclusiveCounts() throws Exception {
        var row = item();
        row.put("state", "ACCEPTED");
        row.put("revision", 2L);
        row.put("attempt_id", 3L);
        row.put("document_id", 4L);
        row.put("version_id", 5L);
        row.put("parse_task_id", 6L);
        var result = MapperRowStub.readOne(configuration(), NS + "item", Map.of("importId", 9L, "id", 2L), row, Item.class);
        assertThat(result.receipt()).isEqualTo(new Receipt(4, 5, 6));
        var counts = MapperRowStub.readOne(configuration(), NS + "counts", Map.of("importId", 9L),
                Map.of("pending", 1, "processing", 2, "accepted", 3, "failed", 4), Counts.class);
        assertThat(counts).isEqualTo(new Counts(1, 2, 3, 4));
    }

    private Map<String, Object> item() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", Long.MAX_VALUE);
        row.put("import_id", 9L);
        row.put("item_key", "one");
        row.put("ordinal", 1);
        row.put("file_name", "原件.txt");
        row.put("title", "标题");
        row.put("size_bytes", 5L);
        row.put("sha256", "a".repeat(64));
        row.put("state", "PENDING");
        row.put("revision", 0L);
        row.put("attempt_id", null);
        row.put("document_id", null);
        row.put("version_id", null);
        row.put("parse_task_id", null);
        row.put("failure_code", null);
        return row;
    }
}

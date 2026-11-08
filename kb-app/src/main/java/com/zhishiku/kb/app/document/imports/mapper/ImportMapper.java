package com.zhishiku.kb.app.document.imports.mapper;

import com.zhishiku.kb.app.document.imports.model.ImportModels.Counts;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FailureCode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FileIntent;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Attempt;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Batch;
import com.zhishiku.kb.app.document.imports.model.ImportRows.Item;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/** 导入账本 SQL，身份检查由服务完成，所有变更仍带持久项资格条件。 */
@Mapper
public interface ImportMapper {
    String BATCH = "id, owner_user_id, create_request_id, manifest_digest, space_id, security_level, item_count, total_bytes, created_at";
    String ITEM = "id, import_id, item_key, ordinal, file_name, title, size_bytes, sha256, state, revision, attempt_id, document_id, version_id, parse_task_id, failure_code";
    String ATTEMPT = "id, item_id, request_id, binding_digest, generation, object_key, state, auth_epoch, started_at, lease_until, ended_at";

    @Select("SELECT nextval('kb_document_import_id_seq')")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    long nextBatchId();

    @Select("SELECT nextval('kb_document_import_attempt_id_seq')")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    long nextAttemptId();

    @Select("SELECT clock_timestamp()")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    OffsetDateTime now();

    @Insert("""
            INSERT INTO kb_document_import(id, owner_user_id, create_request_id, manifest_digest, space_id,
                                           security_level, item_count, total_bytes)
            VALUES (#{id}, #{ownerUserId}, #{createRequestId}, #{manifestDigest}, #{spaceId},
                    #{securityLevel}, #{itemCount}, #{totalBytes})
            ON CONFLICT (owner_user_id, create_request_id) DO NOTHING
            """)
    int insertBatch(Batch batch);

    @Insert("""
            INSERT INTO kb_document_import_item(import_id, item_key, ordinal, file_name, title, size_bytes, sha256)
            VALUES (#{importId}, #{file.itemKey}, #{ordinal}, #{file.fileName}, #{file.title}, #{file.sizeBytes}, #{file.sha256})
            """)
    int insertItem(@Param("importId") long importId, @Param("ordinal") int ordinal, @Param("file") FileIntent file);

    @Select("SELECT " + BATCH + " FROM kb_document_import WHERE id = #{id} AND owner_user_id = #{owner}")
    Batch batch(@Param("owner") long owner, @Param("id") long id);

    @Select("SELECT " + BATCH + " FROM kb_document_import WHERE owner_user_id = #{owner} AND create_request_id = #{requestId}")
    Batch byRequest(@Param("owner") long owner, @Param("requestId") String requestId);

    @Select("""
            <script>SELECT
            """ + BATCH + """
             FROM kb_document_import WHERE owner_user_id = #{owner}
            <if test="before != null">AND id &lt; #{before}</if>
            ORDER BY id DESC LIMIT #{limit}</script>
            """)
    List<Batch> batches(@Param("owner") long owner, @Param("before") Long before, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FILTER (WHERE state = 'PENDING')::int AS pending,
                   COUNT(*) FILTER (WHERE state = 'PROCESSING')::int AS processing,
                   COUNT(*) FILTER (WHERE state = 'ACCEPTED')::int AS accepted,
                   COUNT(*) FILTER (WHERE state = 'FAILED')::int AS failed
            FROM kb_document_import_item WHERE import_id = #{importId}
            """)
    Counts counts(@Param("importId") long importId);

    @Select("SELECT " + ITEM + " FROM kb_document_import_item WHERE import_id = #{importId} AND ordinal > #{after} ORDER BY ordinal LIMIT #{limit}")
    List<Item> items(@Param("importId") long importId, @Param("after") int after, @Param("limit") int limit);

    @Select("SELECT " + ITEM + " FROM kb_document_import_item WHERE import_id = #{importId} AND id = #{id}")
    Item item(@Param("importId") long importId, @Param("id") long id);

    @Select("SELECT " + ITEM + " FROM kb_document_import_item WHERE id = #{id} FOR UPDATE")
    Item lockItem(@Param("id") long id);

    @Select("SELECT " + ITEM + " FROM kb_document_import_item WHERE id = #{id} FOR UPDATE SKIP LOCKED")
    Item tryLockItem(@Param("id") long id);

    @Select("SELECT " + ATTEMPT + " FROM kb_document_import_attempt WHERE item_id = #{itemId} AND request_id = #{requestId}")
    Attempt attemptByRequest(@Param("itemId") long itemId, @Param("requestId") String requestId);

    @Select("SELECT " + ATTEMPT + " FROM kb_document_import_attempt WHERE id = #{id}")
    Attempt attempt(@Param("id") long id);

    @Select("SELECT id FROM kb_space WHERE id = #{spaceId} AND archived = false FOR SHARE")
    Long shareSpace(@Param("spaceId") long spaceId);

    @Insert("""
            INSERT INTO kb_document_import_attempt(id, item_id, request_id, binding_digest, generation,
                                                   object_key, state, auth_epoch, started_at, lease_until)
            VALUES (#{id}, #{itemId}, #{requestId}, #{bindingDigest}, #{generation}, #{objectKey}, 'ACTIVE',
                    #{authEpoch}, #{startedAt}, #{leaseUntil})
            """)
    int insertAttempt(Attempt attempt);

    @Update("""
            UPDATE kb_document_import_item SET state = 'PROCESSING', revision = revision + 1,
                   attempt_id = #{attemptId}, failure_code = NULL
            WHERE id = #{id} AND revision = #{revision} AND state IN ('PENDING', 'FAILED')
            """)
    int claim(@Param("id") long id, @Param("revision") long revision, @Param("attemptId") long attemptId);

    @Update("""
            UPDATE kb_document_import_item SET state = 'ACCEPTED', revision = revision + 1,
                   document_id = #{receipt.documentId}, version_id = #{receipt.versionId}, parse_task_id = #{receipt.parseTaskId}
            WHERE id = #{id} AND attempt_id = #{attemptId} AND revision = #{generation} AND state = 'PROCESSING'
              AND EXISTS (SELECT 1 FROM kb_document_import_attempt a WHERE a.id = kb_document_import_item.attempt_id
                          AND a.state = 'ACTIVE' AND a.lease_until > clock_timestamp())
            """)
    int accept(@Param("id") long id, @Param("attemptId") long attemptId, @Param("generation") long generation,
               @Param("receipt") Receipt receipt);

    @Update("""
            UPDATE kb_document_import_attempt SET state = #{state}, ended_at = clock_timestamp()
            WHERE id = #{id} AND state = 'ACTIVE'
            """)
    int finish(@Param("id") long id, @Param("state") String state);

    @Update("""
            UPDATE kb_document_import_item SET state = 'FAILED', revision = revision + 1, failure_code = #{code}
            WHERE id = #{id} AND attempt_id = #{attemptId} AND revision = #{generation} AND state = 'PROCESSING'
            """)
    int fail(@Param("id") long id, @Param("attemptId") long attemptId, @Param("generation") long generation,
             @Param("code") FailureCode code);

    @Select("""
            SELECT i.id FROM kb_document_import_item i JOIN kb_document_import_attempt a ON a.id = i.attempt_id
            WHERE a.state = 'ACTIVE' AND a.lease_until <= clock_timestamp() AND i.state = 'PROCESSING'
            ORDER BY a.lease_until, i.id LIMIT #{limit}
            """)
    List<Long> expired(@Param("limit") int limit);
}

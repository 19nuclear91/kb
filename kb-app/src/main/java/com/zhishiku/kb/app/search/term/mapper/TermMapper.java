package com.zhishiku.kb.app.search.term.mapper;

import com.zhishiku.kb.app.search.term.TermRows;
import com.zhishiku.kb.app.search.term.TermInputs;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.ResultMap;

import java.time.OffsetDateTime;
import java.util.List;

/** 精确词典事实访问，所有调用具有 JDBC 等待上限且不使用二级缓存。 */
@Mapper
public interface TermMapper {
    @Select("SELECT set_config('statement_timeout', '5000', true), set_config('lock_timeout', '5000', true)")
    @Options(timeout = 5, useCache = false)
    String manageTimeouts();

    @Select("SELECT revision, clock_timestamp() AS database_time FROM kb_search_dictionary_state WHERE id = 1")
    @ConstructorArgs({@Arg(column = "revision", javaType = long.class),
            @Arg(column = "database_time", javaType = OffsetDateTime.class)})
    @Options(timeout = 5, useCache = false)
    TermRows.State state();

    @Select("SELECT revision FROM kb_search_dictionary_state WHERE id = 1 FOR UPDATE")
    @Options(timeout = 5, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long lockState();

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    @Options(timeout = 5, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int lockRequest(@Param("key") String key);

    @Select("""
            SELECT id, canonical_name, canonical_key, description, enabled, version, created_at, updated_at
            FROM kb_search_term WHERE id = #{id}
            """)
    @ConstructorArgs({@Arg(column = "id", javaType = long.class, id = true),
            @Arg(column = "canonical_name", javaType = String.class), @Arg(column = "canonical_key", javaType = String.class),
            @Arg(column = "description", javaType = String.class), @Arg(column = "enabled", javaType = boolean.class),
            @Arg(column = "version", javaType = long.class), @Arg(column = "created_at", javaType = OffsetDateTime.class),
            @Arg(column = "updated_at", javaType = OffsetDateTime.class)})
    @Results(id = "termRow")
    @Options(timeout = 5, useCache = false)
    TermRows.Term term(@Param("id") long id);

    @Select("SELECT term_id, alias_text, lookup_key, kind FROM kb_search_term_alias WHERE term_id = #{id} ORDER BY lookup_key LIMIT 21")
    @ConstructorArgs({@Arg(column = "term_id", javaType = long.class), @Arg(column = "alias_text", javaType = String.class),
            @Arg(column = "lookup_key", javaType = String.class), @Arg(column = "kind", javaType = TermRows.AliasKind.class)})
    @Options(timeout = 5, useCache = false)
    List<TermRows.Alias> aliases(@Param("id") long id);

    @Select("""
            SELECT id, wrong_expression, wrong_key, target_term_id, enabled, version, created_at, updated_at
            FROM kb_search_correction WHERE id = #{id}
            """)
    @ConstructorArgs({@Arg(column = "id", javaType = long.class, id = true),
            @Arg(column = "wrong_expression", javaType = String.class), @Arg(column = "wrong_key", javaType = String.class),
            @Arg(column = "target_term_id", javaType = long.class), @Arg(column = "enabled", javaType = boolean.class),
            @Arg(column = "version", javaType = long.class), @Arg(column = "created_at", javaType = OffsetDateTime.class),
            @Arg(column = "updated_at", javaType = OffsetDateTime.class)})
    @Results(id = "correctionRow")
    @Options(timeout = 5, useCache = false)
    TermRows.Correction correction(@Param("id") long id);

    @Select("""
            SELECT request_id, operation, actor_user_id, target_kind, target_id, expected_version,
                digest_version, input_digest, receipt::text AS receipt, created_at
            FROM kb_search_term_command WHERE request_id = #{requestId}
            """)
    @ConstructorArgs({@Arg(column = "request_id", javaType = String.class), @Arg(column = "operation", javaType = TermRows.Operation.class),
            @Arg(column = "actor_user_id", javaType = long.class), @Arg(column = "target_kind", javaType = TermRows.EntityKind.class),
            @Arg(column = "target_id", javaType = Long.class), @Arg(column = "expected_version", javaType = Long.class),
            @Arg(column = "digest_version", javaType = int.class), @Arg(column = "input_digest", javaType = String.class),
            @Arg(column = "receipt", javaType = String.class), @Arg(column = "created_at", javaType = OffsetDateTime.class)})
    @Options(timeout = 5, useCache = false)
    TermRows.Command command(@Param("requestId") String requestId);

    @Insert("""
            INSERT INTO kb_search_term_command (request_id, operation, actor_user_id, target_kind, target_id,
                expected_version, digest_version, input_digest, receipt)
            VALUES (#{requestId}, #{operation}, #{actorUserId}, #{targetKind}, #{targetId}, #{expectedVersion},
                #{digestVersion}, #{inputDigest}, CAST(#{receipt} AS jsonb))
            """)
    @Options(timeout = 5)
    int insertCommand(TermRows.Command row);

    @Select("SELECT id FROM kb_search_term WHERE canonical_key = #{key}")
    @Options(timeout = 5, useCache = false)
    Long canonicalOwner(@Param("key") String key);

    @Select("SELECT EXISTS (SELECT 1 FROM kb_search_correction WHERE wrong_key = #{key})")
    @Options(timeout = 5, useCache = false)
    boolean correctionKeyExists(@Param("key") String key);

    @Select(value = """
            INSERT INTO kb_search_term (canonical_name, canonical_key, description)
            VALUES (#{canonicalName}, #{canonicalKey}, #{description})
            RETURNING id, canonical_name, canonical_key, description, enabled, version, created_at, updated_at
            """, affectData = true)
    @ResultMap("termRow")
    @Options(timeout = 5, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TermRows.Term insertTerm(TermInputs.TermWrite input);

    @Update("""
            UPDATE kb_search_term SET canonical_name = #{input.canonicalName}, canonical_key = #{input.canonicalKey},
                description = #{input.description}, version = version + 1, updated_at = clock_timestamp()
            WHERE id = #{id} AND version = #{input.expectedVersion} AND version < 9223372036854775807
            """)
    @Options(timeout = 5)
    int updateTerm(@Param("id") long id, @Param("input") TermInputs.TermWrite input);

    @Update("""
            UPDATE kb_search_term SET enabled = #{input.enabled}, version = version + 1, updated_at = clock_timestamp()
            WHERE id = #{id} AND version = #{input.expectedVersion} AND version < 9223372036854775807
            """)
    @Options(timeout = 5)
    int enableTerm(@Param("id") long id, @Param("input") TermInputs.EnabledWrite input);

    @Delete("DELETE FROM kb_search_term_alias WHERE term_id = #{id}")
    @Options(timeout = 5)
    int clearAliases(@Param("id") long id);

    @Insert("""
            INSERT INTO kb_search_term_alias (term_id, alias_text, lookup_key, kind)
            VALUES (#{id}, #{alias.text}, #{alias.key}, #{alias.kind})
            """)
    @Options(timeout = 5)
    int insertAlias(@Param("id") long id, @Param("alias") TermInputs.Alias alias);

    @Update("""
            UPDATE kb_search_dictionary_state SET revision = revision + 1
            WHERE id = 1 AND revision = #{revision} AND revision < 9223372036854775807
            """)
    @Options(timeout = 5)
    int advanceRevision(@Param("revision") long revision);

    @Select("SELECT id FROM kb_search_correction WHERE wrong_key = #{key}")
    @Options(timeout = 5, useCache = false)
    Long correctionOwner(@Param("key") String key);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM kb_search_term WHERE canonical_key = #{key})
                OR EXISTS (SELECT 1 FROM kb_search_term_alias WHERE lookup_key = #{key})
            """)
    @Options(timeout = 5, useCache = false)
    boolean termKeyExists(@Param("key") String key);

    @Select(value = """
            INSERT INTO kb_search_correction (wrong_expression, wrong_key, target_term_id)
            VALUES (#{wrongExpression}, #{wrongKey}, #{termId})
            RETURNING id, wrong_expression, wrong_key, target_term_id, enabled, version, created_at, updated_at
            """, affectData = true)
    @ResultMap("correctionRow")
    @Options(timeout = 5, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    TermRows.Correction insertCorrection(TermInputs.CorrectionWrite input);

    @Update("""
            UPDATE kb_search_correction SET wrong_expression = #{input.wrongExpression}, wrong_key = #{input.wrongKey},
                target_term_id = #{input.termId}, version = version + 1, updated_at = clock_timestamp()
            WHERE id = #{id} AND version = #{input.expectedVersion} AND version < 9223372036854775807
            """)
    @Options(timeout = 5)
    int updateCorrection(@Param("id") long id, @Param("input") TermInputs.CorrectionWrite input);

    @Update("""
            UPDATE kb_search_correction SET enabled = #{input.enabled}, version = version + 1, updated_at = clock_timestamp()
            WHERE id = #{id} AND version = #{input.expectedVersion} AND version < 9223372036854775807
            """)
    @Options(timeout = 5)
    int enableCorrection(@Param("id") long id, @Param("input") TermInputs.EnabledWrite input);

    @Select("""
            <script>
            SELECT t.id, t.canonical_name, t.canonical_key, t.description, t.enabled, t.version, t.created_at, t.updated_at
            FROM kb_search_term t WHERE TRUE
            <if test="namePattern != null">
              AND (t.canonical_key LIKE #{namePattern} ESCAPE '\\'
                OR EXISTS (SELECT 1 FROM kb_search_term_alias a WHERE a.term_id = t.id
                  AND a.lookup_key LIKE #{namePattern} ESCAPE '\\'))
            </if>
            <if test="enabled != null">AND t.enabled = #{enabled}</if>
            <if test="afterId != null">AND t.id &gt; #{afterId}</if>
            ORDER BY t.id LIMIT #{fetch}
            </script>
            """)
    @ResultMap("termRow")
    @Options(timeout = 5, useCache = false)
    List<TermRows.Term> terms(@Param("namePattern") String namePattern, @Param("enabled") Boolean enabled,
                             @Param("afterId") Long afterId, @Param("fetch") int fetch);

    @Select("""
            <script>
            SELECT id, wrong_expression, wrong_key, target_term_id, enabled, version, created_at, updated_at
            FROM kb_search_correction WHERE TRUE
            <if test="namePattern != null">AND wrong_key LIKE #{namePattern} ESCAPE '\\'</if>
            <if test="enabled != null">AND enabled = #{enabled}</if>
            <if test="afterId != null">AND id &gt; #{afterId}</if>
            ORDER BY id LIMIT #{fetch}
            </script>
            """)
    @ResultMap("correctionRow")
    @Options(timeout = 5, useCache = false)
    List<TermRows.Correction> corrections(@Param("namePattern") String namePattern, @Param("enabled") Boolean enabled,
                                         @Param("afterId") Long afterId, @Param("fetch") int fetch);

    @Select("""
            <script>
            SELECT t.id, t.canonical_name, t.canonical_key, t.description, t.enabled, t.version, t.created_at, t.updated_at
            FROM kb_search_term t WHERE t.enabled
              AND (t.canonical_key = #{key} OR EXISTS (SELECT 1 FROM kb_search_term_alias a
                WHERE a.term_id = t.id AND a.lookup_key = #{key}))
            <if test="afterId != null">AND t.id &gt; #{afterId}</if>
            ORDER BY t.id LIMIT #{fetch}
            </script>
            """)
    @ResultMap("termRow")
    @Options(timeout = 5, useCache = false)
    List<TermRows.Term> meanings(@Param("key") String key, @Param("afterId") Long afterId, @Param("fetch") int fetch);

    @Select("""
            <script>
            SELECT candidate.lookup_key, EXISTS (
              SELECT 1 FROM kb_search_term t WHERE t.enabled
                AND (t.canonical_key = candidate.lookup_key COLLATE "C" OR EXISTS (
                  SELECT 1 FROM kb_search_term_alias a WHERE a.term_id = t.id
                    AND a.lookup_key = candidate.lookup_key COLLATE "C"))
              ORDER BY t.id LIMIT 1 OFFSET 1
            ) AS ambiguous
            FROM (VALUES <foreach collection="keys" item="key" separator=",">(#{key})</foreach>) AS candidate(lookup_key)
            </script>
            """)
    @ConstructorArgs({@Arg(column = "lookup_key", javaType = String.class), @Arg(column = "ambiguous", javaType = Boolean.class)})
    @Options(timeout = 5, useCache = false)
    List<TermRows.Expression> ambiguities(@Param("keys") List<String> keys);
}

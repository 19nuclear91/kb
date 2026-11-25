package com.zhishiku.kb.app.auth.mapper.diagnostics;

import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticFacts;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticEntry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Mapper
public interface DiagnosticMapper {
    List<DiagnosticFacts.Acl> sources(
            @Param("spaceId")
            long spaceId,
            @Param("documentId")
            Long documentId,
            @Param("principalKeys")
            Set<String> principalKeys,
            @Param("fetch")
            int fetch);

    List<DiagnosticFacts.Grant> grants(
            @Param("spaceId")
            long spaceId,
            @Param("documentId")
            Long documentId,
            @Param("principalKeys")
            Set<String> principalKeys,
            @Param("rows")
            List<DiagnosticFacts.Acl> rows,
            @Param("globalManage")
            boolean globalManage);

    DiagnosticFacts.Current current(
            @Param("spaceId")
            long spaceId,
            @Param("documentId")
            long documentId,
            @Param("clearance")
            short clearance,
            @Param("principalKeys")
            Set<String> principalKeys,
            @Param("rows")
            List<DiagnosticFacts.Acl> rows);

    @Select("SELECT transaction_timestamp()")
    OffsetDateTime evaluatedAt();

    @Select("SELECT set_config('statement_timeout', #{millis}, true)")
    String timeout(
            @Param("millis")
            String millis);

    List<String> existingEntries(
            @Param("spaceId")
            long spaceId,
            @Param("documentId")
            Long documentId,
            @Param("entries")
            List<DiagnosticEntry> entries);

    @Select("""
            <script>SELECT id FROM sys_role WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> existingRoles(
            @Param("ids")
            Set<Long> ids);
}

package com.zhishiku.kb.app.document.diff.mapper;

import com.zhishiku.kb.app.document.diff.model.SnapshotBlock;
import com.zhishiku.kb.app.document.diff.model.SnapshotHeader;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentDiffMapper {
    List<SnapshotHeader> headers(@Param("documentId") long documentId,
                                 @Param("baseVersionId") long baseVersionId,
                                 @Param("targetVersionId") long targetVersionId);

    List<SnapshotBlock> blocks(@Param("versionId") long versionId);

    @Select("""
            SELECT COALESCE(SUM(octet_length(text)::bigint + COALESCE(octet_length(heading_path), 0)
                + COALESCE(octet_length(table_cells::text), 0) + COALESCE(octet_length(locator), 0)), 0)
            FROM kb_document_version_block WHERE version_id=#{versionId}
            """)
    @Options(timeout = 3)
    long materialBytes(@Param("versionId") long versionId);
}

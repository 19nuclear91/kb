package com.zhishiku.kb.app.document.diff.mapper;

import com.zhishiku.kb.app.document.diff.model.SnapshotBlock;
import com.zhishiku.kb.app.document.diff.model.SnapshotHeader;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentDiffMapper {
    List<SnapshotHeader> headers(@Param("documentId") long documentId,
                                 @Param("baseVersionId") long baseVersionId,
                                 @Param("targetVersionId") long targetVersionId);

    List<SnapshotBlock> blocks(@Param("versionId") long versionId);
}

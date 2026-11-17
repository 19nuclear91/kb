package com.zhishiku.kb.app.document.changenote.mapper;

import com.zhishiku.kb.app.document.changenote.model.ChangeNoteRow;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteTarget;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ChangeNoteMapper {
    ChangeNoteTarget target(@Param("documentId") long document, @Param("versionId") long version);
    ChangeNoteTarget lockVersion(@Param("documentId") long document, @Param("versionId") long version);
    ChangeNoteRow get(@Param("documentId") long document, @Param("versionId") long version);
    record Base(long versionId, int versionNo) {
    }
    Base base(@Param("documentId") long document, @Param("versionId") long version);
    int insert(ChangeNoteRow row);
    int update(ChangeNoteRow row);
    int appendHistory(ChangeNoteHistory row);
    List<ChangeNoteHistory> history(@Param("documentId") long document, @Param("versionId") long version,
                                    @Param("beforeTime") OffsetDateTime time, @Param("beforeId") Long id,
                                    @Param("limit") int limit);
    boolean hasPermission(@Param("documentId") long documentId, @Param("spaceIds") List<Long> spaceIds,
                          @Param("principalKeys") List<String> keys, @Param("clearance") int clearance,
                          @Param("aclPermission") String permission);
}

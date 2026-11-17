package com.zhishiku.kb.app.document.changenote.mapper;

import com.zhishiku.kb.app.document.changenote.model.ChangeNoteCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ChangeNoteCommandMapper {
    int lockRequest(@Param("key") String key);
    ChangeNoteCommand get(@Param("scope") String scope, @Param("requestId") String requestId);
    int insert(ChangeNoteCommand row);
}

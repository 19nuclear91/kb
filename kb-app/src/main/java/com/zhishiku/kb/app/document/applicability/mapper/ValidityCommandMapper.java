package com.zhishiku.kb.app.document.applicability.mapper;

import com.zhishiku.kb.app.document.applicability.model.ValidityCommandRow;
import org.apache.ibatis.annotations.Param;

public interface ValidityCommandMapper {
    int lockRequest(@Param("key") String key);
    ValidityCommandRow get(@Param("scope") String scope, @Param("requestId") String requestId);
    int insert(ValidityCommandRow row);
}

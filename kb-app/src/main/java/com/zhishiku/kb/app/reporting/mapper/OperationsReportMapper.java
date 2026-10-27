package com.zhishiku.kb.app.reporting.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 仅查询固定聚合，不读取完整业务实体或正文。 */
@Mapper
public interface OperationsReportMapper {
    Instant asOf();

    Map<String, Object> documents();

    List<Map<String, Object>> versions();

    Map<String, Object> pendingReviews();

    List<Map<String, Object>> coverage();

    List<Map<String, Object>> events(@Param("from") Instant from, @Param("to") Instant to,
                                     @Param("reviewFrom") Instant reviewFrom,
                                     @Param("commentFrom") Instant commentFrom);
}

package com.zhishiku.kb.app.document.stewardship.mapper;

import com.zhishiku.kb.app.document.stewardship.model.StewardCandidate;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipDocument;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipListRow;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipRow;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

public interface StewardshipMapper {
    StewardshipDocument document(@Param("documentId") long documentId);
    StewardshipDocument shareDocument(@Param("documentId") long documentId);
    StewardshipRow get(@Param("documentId") long documentId);
    StewardshipRow lock(@Param("documentId") long documentId);
    int insert(StewardshipRow row);
    int update(StewardshipRow row);
    StewardCandidate responsible(@Param("documentId") long documentId);
    boolean eligible(@Param("documentId") long documentId, @Param("userId") long userId);
    List<StewardCandidate> candidates(@Param("documentId") long documentId, @Param("namePattern") String namePattern,
                                      @Param("afterId") Long afterId, @Param("limit") int limit);
    List<StewardshipListRow> mine(@Param("actor") long actor, @Param("view") String view, @Param("today") LocalDate today,
                                 @Param("spaceIds") List<Long> spaceIds, @Param("principalKeys") List<String> principalKeys,
                                 @Param("clearance") short clearance, @Param("afterDate") LocalDate afterDate,
                                 @Param("afterId") Long afterId, @Param("limit") int limit);
    String count(@Param("actor") long actor, @Param("view") String view, @Param("today") LocalDate today,
                 @Param("spaceIds") List<Long> spaceIds, @Param("principalKeys") List<String> principalKeys,
                 @Param("clearance") short clearance);
    List<StewardshipListRow> space(@Param("spaceId") long spaceId, @Param("state") String state, @Param("today") LocalDate today,
                                  @Param("afterDate") LocalDate afterDate, @Param("afterId") Long afterId, @Param("limit") int limit);
}

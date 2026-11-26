package com.zhishiku.kb.app.mapper.answercard;

import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceFact;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceKey;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AnswerCardSourceMapper {
    List<AnswerCardSourceFact> facts(
            @Param("sources")
            List<AnswerCardSourceKey> sources,
            @Param("spaceIds")
            List<Long> spaceIds,
            @Param("principalKeys")
            List<String> principalKeys,
            @Param("clearance")
            short clearance
    );

    List<AnswerCardSourceFact> candidates(
            @Param("namePattern")
            String namePattern,
            @Param("afterId")
            Long afterId,
            @Param("limit")
            int limit,
            @Param("spaceIds")
            List<Long> spaceIds,
            @Param("principalKeys")
            List<String> principalKeys,
            @Param("clearance")
            short clearance
    );
}

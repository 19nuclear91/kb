package com.zhishiku.kb.app.parse.mapper;

import com.zhishiku.kb.app.parse.model.ParseDeadLetterView;
import com.zhishiku.kb.app.parse.model.ParseReplayBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ParseDeadLetterMapper {
    List<ParseDeadLetterView> list(@Param("status") String status,
                                   @Param("failureClass") String failureClass,
                                   @Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to,
                                   @Param("offset") long offset,
                                   @Param("limit") int limit);

    long count(@Param("status") String status,
               @Param("failureClass") String failureClass,
               @Param("from") OffsetDateTime from,
               @Param("to") OffsetDateTime to);

    ParseDeadLetterView findByReplayRequest(@Param("requestId") String requestId);

    @Select("SELECT set_config('lock_timeout', '2s', true)")
    String limitLockWait();

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE, timeout = 5)
    int lockReplayRequest(@Param("key") String key);

    ParseReplayBinding findReplayBinding(@Param("requestId") String requestId);

    int replay(@Param("deadLetterId") long deadLetterId,
               @Param("requestId") String requestId,
               @Param("actorUserId") long actorUserId,
               @Param("reason") String reason);
}

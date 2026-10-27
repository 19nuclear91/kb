package com.zhishiku.kb.app.embedding.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface EmbeddingControlMapper {
    @Update("""
            UPDATE kb_embedding_control SET state = #{nextState}, epoch = epoch + 1
             WHERE id = 1 AND state = #{expectedState} AND epoch = #{expectedEpoch} AND run_id = #{runId}
            """)
    int advance(@Param("runId") UUID runId, @Param("expectedState") String expectedState,
                @Param("expectedEpoch") long expectedEpoch, @Param("nextState") String nextState);

    @Update("""
            UPDATE kb_embedding_rebuild SET state = #{nextState}, version = version + 1, updated_at = now()
             WHERE run_id = #{runId} AND state = #{expectedState} AND version = #{expectedVersion}
            """)
    int advanceRun(@Param("runId") UUID runId, @Param("expectedState") String expectedState,
                   @Param("expectedVersion") long expectedVersion, @Param("nextState") String nextState);
}

package com.zhishiku.kb.app.document.mapper;

import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.model.ReviewVersionState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.time.Instant;
import java.util.UUID;
import com.zhishiku.kb.common.review.ReviewState;

@Mapper
public interface DocumentReviewMapper {
    ReviewVersionState version(@Param("documentId") long documentId, @Param("versionId") long versionId);
    @Select("SELECT id FROM kb_document WHERE id = #{documentId} FOR UPDATE")
    Long lockDocument(@Param("documentId") long documentId);

    ReviewVersionState lockVersionRow(@Param("documentId") long documentId, @Param("versionId") long versionId);

    default ReviewVersionState lockVersion(long documentId, long versionId) {
        if (lockDocument(documentId) == null) return null;
        return lockVersionRow(documentId, versionId);
    }
    List<ReviewVersionState> lockVersionsForDocument(@Param("documentId") long documentId);

    boolean hasPermission(@Param("documentId") long documentId, @Param("spaceIds") List<Long> spaceIds,
                          @Param("principalKeys") List<String> principalKeys, @Param("clearance") short clearance,
                          @Param("permission") String permission);

    @Select("SELECT EXISTS (SELECT 1 FROM kb_document_review WHERE version_id = #{versionId} AND submitted_by = #{actor})")
    boolean hasSubmitted(@Param("versionId") long versionId, @Param("actor") long actor);

    @Select("""
            SELECT id, doc_id, version_id, round_no, submitted_by, submitted_space_id, state,
                state_version, submitted_at, decision_by, decided_at
            FROM kb_document_review WHERE version_id = #{versionId} ORDER BY round_no DESC LIMIT 1
            """)
    DocumentReviewRow latest(@Param("versionId") long versionId);

    @Select("SELECT * FROM kb_document_review WHERE version_id = #{versionId} AND id = #{reviewId} FOR UPDATE")
    DocumentReviewRow lockReview(@Param("versionId") long versionId, @Param("reviewId") UUID reviewId);

    @Select("SELECT * FROM kb_document_review WHERE version_id = #{versionId} AND id = #{reviewId}")
    DocumentReviewRow review(@Param("versionId") long versionId, @Param("reviewId") UUID reviewId);

    @Select("""
            SELECT * FROM kb_document_review WHERE version_id = #{versionId} AND round_no < #{beforeRound}
            ORDER BY round_no DESC LIMIT #{limit}
            """)
    List<DocumentReviewRow> privateRounds(@Param("versionId") long versionId,
                                           @Param("beforeRound") long beforeRound, @Param("limit") int limit);

    @Update("""
            UPDATE kb_document_review SET state = #{state}, state_version = state_version + 1,
                decision_by = #{actor}, decided_at = #{now}, reason = #{reason}
            WHERE id = #{reviewId} AND version_id = #{versionId} AND state = 'IN_REVIEW'
                AND state_version = #{expectedRoundVersion}
            """)
    int decide(@Param("versionId") long versionId, @Param("reviewId") UUID reviewId,
                @Param("expectedRoundVersion") long expectedRoundVersion, @Param("state") ReviewState state,
                @Param("actor") long actor, @Param("now") Instant now, @Param("reason") String reason);

    @Insert("""
            INSERT INTO kb_document_review (id, doc_id, version_id, round_no, submitted_by, submitted_space_id,
                state, state_version, submitted_at)
            VALUES (#{id}, #{docId}, #{versionId}, #{roundNo}, #{submittedBy}, #{submittedSpaceId},
                #{state}, 0, #{submittedAt})
            """)
    int insert(DocumentReviewRow round);

    @Update("""
            UPDATE kb_document_version SET state_version = state_version + 1
            WHERE id = #{versionId} AND doc_id = #{documentId} AND state_version = #{expectedVersion}
            """)
    int bumpVersion(@Param("documentId") long documentId, @Param("versionId") long versionId,
                    @Param("expectedVersion") long expectedVersion);
}

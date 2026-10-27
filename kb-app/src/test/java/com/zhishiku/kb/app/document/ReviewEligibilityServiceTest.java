package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.document.mapper.DocumentReviewEligibilityMapper;
import com.zhishiku.kb.app.document.model.ReviewerQualification;
import com.zhishiku.kb.app.document.service.ReviewEligibilityService;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewEligibilityServiceTest {
    private final DocumentReviewEligibilityMapper mapper = mock(DocumentReviewEligibilityMapper.class);
    private final ReviewEligibilityService service = new ReviewEligibilityService(mapper);

    @Test
    void everyIndependentQualificationAndBothSelfExclusionsAreRequired() {
        for (boolean enabled : List.of(false, true))
            for (boolean department : List.of(false, true))
                for (boolean view : List.of(false, true))
                    for (boolean review : List.of(false, true))
                        for (short clearance : new short[]{1, 2}) {
                            var row = new ReviewerQualification(7, enabled, department, clearance, (short) 2, view, review);
                            when(mapper.candidates(10, 8L, 9L, 7L)).thenReturn(List.of(row));
                            assertThat(service.eligible(7, 10, 8L, 9L))
                                    .isEqualTo(enabled && department && view && review && clearance >= 2);
                            assertThat(row.eligible(7L, 9L)).isFalse();
                            assertThat(row.eligible(8L, 7L)).isFalse();
                        }
    }

    @Test
    void poolDeduplicatesAndExcludesUploaderAndCurrentSubmitterNotOwner() {
        var uploader = qualified(7);
        var submitter = qualified(8);
        var owner = qualified(9);
        when(mapper.candidates(10, 7L, 8L, null)).thenReturn(List.of(uploader, submitter, owner, owner));
        assertThat(service.requirePool(10, 7L, 8)).containsExactly(9L);
        when(mapper.candidates(10, 7L, 8L, 9L)).thenReturn(List.of(owner));
        assertThat(service.eligible(9, 10, 7L, 8L)).isTrue();
        when(mapper.candidates(10, 7L, 8L, 9L)).thenReturn(List.of());
        assertThat(service.eligible(9, 10, 7L, 8L)).isFalse();
    }

    @Test
    void emptyPoolAndFailedDependencyNeverFallBackToAutoOrManager() {
        when(mapper.candidates(10, 7L, 8L, null)).thenReturn(List.of(qualified(7), qualified(8)));
        assertThatThrownBy(() -> service.requirePool(10, 7L, 8)).isInstanceOf(ReviewFailure.class)
                .extracting(error -> ((ReviewFailure) error).error()).isEqualTo(ReviewError.NO_ELIGIBLE_REVIEWER);
        when(mapper.candidates(10, 7L, 8L, null)).thenReturn(null);
        assertThatThrownBy(() -> service.requirePool(10, 7L, 8)).isInstanceOf(ReviewFailure.class)
                .extracting(error -> ((ReviewFailure) error).error()).isEqualTo(ReviewError.SERVICE_UNAVAILABLE);
    }

    private static ReviewerQualification qualified(long id) {
        return new ReviewerQualification(id, true, true, (short) 2, (short) 2, true, true);
    }
}

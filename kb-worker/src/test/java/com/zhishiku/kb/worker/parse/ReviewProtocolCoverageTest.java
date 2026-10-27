package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.mapper.ReviewFactMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ReviewProtocolCoverageTest {
    @Test
    void workerRecordsNewProtocolStartWithoutInventingHistoricalPublicationEvents() {
        var mapper = mock(ReviewFactMapper.class);
        var coverage = new ReviewProtocolCoverage(mapper);
        coverage.start();
        coverage.start();
        verify(mapper, times(2)).startCoverage(any());
        verify(mapper, never()).append(any());
    }
}

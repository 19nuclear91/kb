package com.zhishiku.kb.app.auth.lifecycle;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class OutputStopWindowTest {
    private final Instant origin = Instant.parse("2026-11-06T00:00:00Z");

    @Test
    void receiptAndMonotonicStopIntervalsProduceMeasuredBoundsWithoutAiWallClock() {
        var window = calculate(origin.plusSeconds(1), origin.plusSeconds(2), 5_000_000_000L, 6_000_000_000L);
        assertThat(window.quality()).isEqualTo(OutputStopWindow.Quality.CONFIRMED);
        assertThat(window.lowerSeconds()).isEqualTo(3);
        assertThat(window.upperSeconds()).isEqualTo(5);
        var breach = calculate(origin.plusSeconds(1), origin.plusSeconds(2), 33_000_000_000L, 34_000_000_000L);
        assertThat(breach.lowerSeconds()).isEqualTo(31);
    }

    @Test
    void missingCommitObservationOverlappingIntervalOrBackwardClockCannotBecomeZeroSuccess() {
        assertThat(calculate(origin.plusSeconds(1), null, 5_000_000_000L, 6_000_000_000L).quality()).isEqualTo(OutputStopWindow.Quality.UNCERTAIN);
        assertThat(calculate(origin.plusSeconds(2), origin.plusSeconds(1), 5_000_000_000L, 6_000_000_000L).quality()).isEqualTo(OutputStopWindow.Quality.UNCERTAIN);
        assertThat(calculate(origin.plusSeconds(1), origin.plusSeconds(2), 1_000_000_000L, 3_000_000_000L).quality()).isEqualTo(OutputStopWindow.Quality.UNCERTAIN);
        assertThat(calculate(origin.plusSeconds(1), origin.plusSeconds(2), 5_000_000_000L, 8_000_000_001L).quality()).isEqualTo(OutputStopWindow.Quality.UNCERTAIN);
    }

    @Test
    void restorationCannotChangeOriginalEpochLinkAndDeferredMappingIsNotInitialReceptionSla() {
        assertThat(OutputStopWindow.calculate(false, true, null, null, null, null, origin, 1, 2, origin)
                .quality()).isEqualTo(OutputStopWindow.Quality.NOT_LINKED);
        assertThat(OutputStopWindow.calculate(true, false, origin, origin, origin, origin, origin, 1, 2, origin)
                .quality()).isEqualTo(OutputStopWindow.Quality.DEFERRED);
    }

    private OutputStopWindow calculate(Instant before, Instant after, long min, long max) {
        return OutputStopWindow.calculate(true, true, before, after, origin.plusSeconds(1), origin.plusSeconds(2),
                origin, min, max, origin.plusSeconds(60));
    }
}

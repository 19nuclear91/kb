package com.zhishiku.kb.app.document.preview;

import com.zhishiku.kb.app.document.model.DocumentFile;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficePreviewSessionsTest {
    @Test
    void oneTimeHandoffVersionBindingAndIndependentTabs() {
        var fixture = new Fixture();
        var first = fixture.service.create("login", 8, 9);
        var second = fixture.service.create("login", 8, 10);
        assertThat(first.toString()).doesNotContain(first.launchToken(), "login");
        fail(() -> fixture.service.activate(first.sessionId(), first.launchToken(), "https://foreign.invalid"), 404);
        var active = fixture.service.activate(first.sessionId(), first.launchToken(), fixture.settings.getApplicationOrigin());
        var other = fixture.service.activate(second.sessionId(), second.launchToken(), fixture.settings.getApplicationOrigin());
        fail(() -> fixture.service.activate(first.sessionId(), first.launchToken(), fixture.settings.getApplicationOrigin()), 404);
        var authorized = fixture.service.authorize(first.sessionId(), active.cookie());
        assertThat(authorized.session().versionId()).isEqualTo(9);
        assertThat(authorized.session().documentKey()).isNotEqualTo(fixture.service.authorize(second.sessionId(), other.cookie()).session().documentKey());
        fail(() -> fixture.service.authorize(first.sessionId(), other.cookie()), 404);
        fixture.service.close(first.sessionId(), "another-login-same-user");
        fixture.service.authorize(first.sessionId(), active.cookie());
        fixture.service.close(first.sessionId(), "login");
        fixture.service.close(first.sessionId(), "login");
        fail(() -> fixture.service.heartbeat(first.sessionId(), "login"), 404);
        fixture.service.authorize(second.sessionId(), other.cookie());
    }

    @Test
    void pendingQuotaLeaseAndAbsoluteExpiryStayBounded() {
        var fixture = new Fixture();
        fixture.settings.setMaxPerUser(1);
        fixture.settings.setAbsoluteSeconds(90);
        fixture.service.create("login", 8, 9);
        fail(() -> fixture.service.create("login", 8, 10), 429);
        fixture.clock.advance(61);
        var launch = fixture.service.create("login", 8, 10);
        var active = fixture.service.activate(launch.sessionId(), launch.launchToken(), fixture.settings.getApplicationOrigin());
        for (int i = 0; i < 5; i++) {
            fixture.clock.advance(15);
            var lease = fixture.service.heartbeat(launch.sessionId(), "login");
            assertThat(lease.expiresAt()).isLessThanOrEqualTo(launch.expiresAt());
        }
        fixture.clock.advance(16);
        fail(() -> fixture.service.authorize(launch.sessionId(), active.cookie()), 404);
        var lost = fixture.service.create("login", 8, 9);
        var lostActive = fixture.service.activate(lost.sessionId(), lost.launchToken(), fixture.settings.getApplicationOrigin());
        fixture.clock.advance(46);
        fail(() -> fixture.service.authorize(lost.sessionId(), lostActive.cookie()), 404);
    }

    @Test
    void newLoginRevocationAndDisabledSwitchNeverKeepAReaderAlive() {
        var fixture = new Fixture();
        var launch = fixture.service.create("login", 8, 9);
        var active = fixture.service.activate(launch.sessionId(), launch.launchToken(), fixture.settings.getApplicationOrigin());
        fail(() -> fixture.service.heartbeat(launch.sessionId(), "new-login"), 404);
        fixture.settings.setEnabled(false);
        fail(() -> fixture.service.authorize(launch.sessionId(), active.cookie()), 503);
        fixture.settings.setEnabled(true);
        doThrow(new OfficePreviewFailure(404)).when(fixture.access).require("login", 7L, 8, 9);
        fail(() -> fixture.service.source(launch.sessionId()), 404);
        fail(() -> fixture.service.authorize(launch.sessionId(), active.cookie()), 404);
    }

    static void fail(Runnable request, int status) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(OfficePreviewFailure.class,
                failure -> assertThat(failure.status()).isEqualTo(status));
    }

    private static final class Fixture {
        final OfficePreviewSettings settings = new OfficePreviewSettings();
        final OfficePreviewAccess access = mock(OfficePreviewAccess.class);
        final MutableClock clock = new MutableClock();
        final OfficePreviewSessions service = new OfficePreviewSessions(settings, new MemoryOfficePreviewStore(), access, clock);

        Fixture() {
            settings.setEnabled(true);
            settings.setApplicationOrigin("https://kb.example.invalid");
            settings.setPreviewOrigin("https://preview.example.invalid");
            when(access.require(anyString(), any(), anyLong(), anyLong())).thenAnswer(call -> {
                long doc = call.getArgument(2);
                long version = call.getArgument(3);
                return new OfficePreviewAccess.Access(7, new DocumentFile(doc, version, 1, "key", "file.docx", 10L, "application/octet-stream"));
            });
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-05T00:00:00Z");

        void advance(int seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

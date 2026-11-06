package com.zhishiku.kb.app.auth.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DingTalkMembershipTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LifecycleHttp http = mock(LifecycleHttp.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final DingTalkMembership membership = new DingTalkMembership(DingTalkLifecycleSettingsTest.configured(false), http, clock);

    @Test
    void exactIdentifierQueryWorksWhileReceiverDisabledAndCachesOnlyApplicationToken() throws Exception {
        token();
        when(http.post(contains("/user/get?"), eq(Map.of("userid", "staff-7"))))
                .thenReturn(json.readTree("{\"errcode\":0,\"result\":{\"userid\":\"staff-7\",\"name\":\"员工\",\"active\":true}}"));
        var employee = membership.current("staff-7");
        assertThat(employee.employeeId()).isEqualTo("staff-7");
        assertThat(employee.enterpriseId()).isEqualTo("ding-corp");
        assertThat(employee.checkedAt()).isEqualTo(clock.instant());
        assertThat(employee.toString()).doesNotContain("staff", "员工");
        membership.current("staff-7");
        verify(http, times(1)).post(contains("oauth2/accessToken"), any());
        verify(http, times(2)).post(contains("/user/get?"), eq(Map.of("userid", "staff-7")));
    }

    @Test
    void invisibleInactiveWrongIdentityMalformedAndDependencyFailureNeverYieldEvidence() throws Exception {
        token();
        for (String response : java.util.List.of("{\"errcode\":60121}", "{\"errcode\":0}",
                "{\"errcode\":0,\"result\":{\"userid\":\"other\",\"active\":true}}",
                "{\"errcode\":0,\"result\":{\"userid\":\"staff-7\",\"active\":false}}",
                "{\"errcode\":0,\"result\":{\"userid\":\"staff-7\",\"active\":\"true\"}}")) {
            when(http.post(contains("/user/get?"), any())).thenReturn(json.readTree(response));
            assertThatThrownBy(() -> membership.current("staff-7")).isInstanceOf(ApiException.class)
                    .hasMessageNotContaining("staff-7").hasMessageNotContaining("60121");
        }
        when(http.post(contains("/user/get?"), any())).thenThrow(LifecycleFailures.unavailable());
        assertThatThrownBy(() -> membership.current("staff-7")).isInstanceOf(ApiException.class);
    }

    private void token() throws Exception {
        when(http.post(contains("oauth2/accessToken"), any())).thenReturn(json.readTree("{\"accessToken\":\"app-token\",\"expireIn\":7200}"));
    }
}

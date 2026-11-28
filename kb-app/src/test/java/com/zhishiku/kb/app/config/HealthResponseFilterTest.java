package com.zhishiku.kb.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HealthResponseFilterTest {
    @Test
    void downstreamAndEarlyErrorsCannotOverridePrivatePolicy() throws Exception {
        for (int status : List.of(200, 400, 401, 403, 404, 405, 409, 503)) {
            var request = new MockHttpServletRequest("GET", "/api/admin/spaces/2/health-radar");
            var response = new MockHttpServletResponse();
            new HealthResponseFilter().doFilter(request, response, (req, res) -> {
                var http = (jakarta.servlet.http.HttpServletResponse) res;
                http.setStatus(status);
                http.setHeader("Cache-Control", "public, max-age=100");
            });
            assertThat(response.getStatus()).isEqualTo(status);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }
}

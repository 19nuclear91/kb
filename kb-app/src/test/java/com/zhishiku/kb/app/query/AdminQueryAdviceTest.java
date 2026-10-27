package com.zhishiku.kb.app.query;

import com.zhishiku.kb.app.query.web.AdminQueryAdvice;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminQueryAdviceTest {
    @Test
    void mapsOnlyFixedErrorsAndAddsNoStoreWithoutLeakingCauses() throws Exception {
        var advice = new AdminQueryAdvice();
        for (var failure : List.of(new QueryException(400, "secret-q"), IdentityException.unauthorized("secret-token"),
                IdentityException.forbidden("secret-role"), new DataAccessResourceFailureException("secret-jdbc"),
                new IllegalStateException("secret-sql"))) {
            var response = advice.failure(failure);
            assertThat(response.getBody().message()).doesNotContain("secret");
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        }
        var mvc = MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(advice).build();
        mvc.perform(get("/query-probe")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no" +
                "-store"));
        assertThat(advice.failure(new DataAccessResourceFailureException("secret")).getStatusCode().value()).isEqualTo(503);
        assertThat(advice.failure(new TransactionTimedOutException("secret")).getStatusCode().value()).isEqualTo(503);
        assertThat(advice.failure(new IllegalStateException()).getStatusCode().value()).isEqualTo(500);
    }

    @AdminQueryEndpoint
    @RestController
    static class Probe {
        @GetMapping("/query-probe")
        public R<String> get() {
            return R.ok("value");
        }
    }
}

package com.zhishiku.kb.common.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountRevokedExceptionTest {
    @Test
    void onlySingleExactMarkerAndIdentityRejectionStatusConfirmRevocation() {
        for (int status : new int[]{401, 403}) {
            assertThat(new AccountRevokedException(status).httpStatus()).isEqualTo(status);
            assertThat(AccountRevokedException.confirmed(status, List.of(AccountRevokedException.CODE))).isTrue();
            assertThat(AccountRevokedException.confirmed(status, null)).isFalse();
            assertThat(AccountRevokedException.confirmed(status, List.of())).isFalse();
            assertThat(AccountRevokedException.confirmed(status, List.of("OTHER"))).isFalse();
            assertThat(AccountRevokedException.confirmed(status, List.of(AccountRevokedException.CODE, AccountRevokedException.CODE))).isFalse();
        }
        for (int status : new int[]{200, 400, 409, 500, 503}) {
            assertThat(AccountRevokedException.confirmed(status, List.of(AccountRevokedException.CODE))).isFalse();
            assertThatThrownBy(() -> new AccountRevokedException(status)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

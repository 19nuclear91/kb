package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.service.PermissionService.PrincipalHandle;
import com.zhishiku.kb.app.query.QueryException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthQueryServiceTest {
    @Test
    void currentPermissionsAreSortedAndRefreshedAndCannotSelectAnotherUser() {
        var access = mock(AdminQueryAccess.class);
        var handle = mock(PrincipalHandle.class);
        when(access.current()).thenReturn(handle);
        when(handle.functionalPermissions()).thenReturn(Set.of("z", "a"), Set.of());
        var service = new AuthQueryService(access, mock(SecurityLevelDictionary.class));
        assertThat(service.permissions(Map.of())).containsExactly("a", "z");
        assertThat(service.permissions(Map.of())).isEmpty();
        assertThatThrownBy(() -> service.permissions(Map.of("userId", List.of("99")))).isInstanceOf(QueryException.class);
        when(access.current()).thenThrow(QueryException.forbidden());
        assertThatThrownBy(() -> service.permissions(Map.of())).isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> service.levels(Map.of())).isInstanceOf(QueryException.class);
    }

    @Test
    void dictionaryAllowsNewLevelsAndEmptyButPropagatesDependencyFailure() {
        var access = mock(AdminQueryAccess.class);
        var dictionary = mock(SecurityLevelDictionary.class);
        var service = new AuthQueryService(access, dictionary);
        when(dictionary.availableLevels()).thenReturn(List.of((short) 12, (short) 0, (short) 6, (short) 6), List.of())
                .thenThrow(new DataAccessResourceFailureException("sensitive"));
        assertThat(service.levels(Map.of())).containsExactly((short) 0, (short) 6, (short) 12);
        assertThat(service.levels(Map.of())).isEmpty();
        assertThatThrownBy(() -> service.levels(Map.of())).isInstanceOf(DataAccessResourceFailureException.class);
    }
}

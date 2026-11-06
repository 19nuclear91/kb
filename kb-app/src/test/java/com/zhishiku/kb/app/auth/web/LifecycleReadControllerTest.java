package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.lifecycle.DingTalkLifecycleSettings;
import com.zhishiku.kb.app.auth.lifecycle.DingTalkStreamConnection;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleAuthorizer;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleQueryService;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleQueryMapper;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.query.web.AdminQueryAdvice;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LifecycleReadControllerTest {
    @Test
    void successesDenialsAndDependencyErrorsUseNoStoreAndSafeProjection() throws Exception {
        var auth = mock(LifecycleAuthorizer.class);
        var users = mock(IdentityDepartmentMapper.class);
        var mapping = mock(LifecycleMapper.class);
        var queries = mock(LifecycleQueryMapper.class);
        var service = new LifecycleQueryService(auth, users, mapping, mock(LifecycleInboxMapper.class), queries,
                mock(DingTalkLifecycleSettings.class), mock(DingTalkStreamConnection.class));
        var mvc = MockMvcBuilders.standaloneSetup(new LifecycleReadController(service))
                .setControllerAdvice(new AdminQueryAdvice()).build();
        try (var sessions = mockStatic(StpUtil.class)) {
            sessions.when(StpUtil::getTokenValue).thenReturn("token");
            when(users.findIdentity(7)).thenReturn(new IdentityRecord(7L, "local", "用户", 1L, (short) 1, true, 0L, null, null));
            mvc.perform(get("/api/admin/identity/users/7/lifecycle"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.data.kind").value("NONE"))
                    .andExpect(jsonPath("$.data.manualEnabled").value(true))
                    .andExpect(jsonPath("$.data.employeeId").doesNotExist());
            when(users.findIdentity(9)).thenThrow(new DataAccessResourceFailureException("secret SQL employee"));
            mvc.perform(get("/api/admin/identity/users/9/lifecycle"))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
            doThrow(IdentityException.forbidden("private employee")).when(auth).require("token", Permission.IDENTITY_LIFECYCLE_READ);
            mvc.perform(get("/api/admin/identity/lifecycle/events"))
                    .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"));
            verifyNoInteractions(queries);
        }
    }
}

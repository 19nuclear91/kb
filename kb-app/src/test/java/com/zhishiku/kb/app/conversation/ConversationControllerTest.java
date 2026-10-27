package com.zhishiku.kb.app.conversation;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.config.ConversationPrivacyFilter;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.conversation.model.ConversationView;
import com.zhishiku.kb.app.conversation.model.CreationResult;
import com.zhishiku.kb.app.conversation.service.ConversationService;
import com.zhishiku.kb.app.conversation.service.TurnExecutionService;
import com.zhishiku.kb.app.conversation.web.ConversationController;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationControllerTest {
    @Test
    void publicStatusCodesUnknownFieldsAndNoStoreUseStandaloneMvc() throws Exception {
        var service = mock(ConversationService.class);
        var execution = mock(TurnExecutionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service, execution))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new ConversationPrivacyFilter())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();
        var view = new ConversationView(9, "新会话", Instant.EPOCH, Instant.EPOCH, 0, null);
        when(service.create(1, null, "r", null)).thenReturn(new CreationResult(view, false), new CreationResult(view,
                true));
        when(service.create(1, "other", "r", null)).thenThrow(ApiException.conflict("safe"));
        when(service.create(1, null, "deleted", null)).thenThrow(ApiException.notFound("safe"));
        when(service.detail(1, 9)).thenThrow(ApiException.notFound("safe"));
        doThrow(ApiException.notFound("safe")).when(execution).delete(1, 10);
        try (var auth = mockStatic(StpUtil.class)) {
            auth.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            mvc.perform(post("/api/conversations").contentType("application/json").content("{\"requestId\":\"r\"}"))
                    .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.conversation.id").value(9));
            mvc.perform(post("/api/conversations").contentType("application/json").content("{\"requestId\":\"r\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
            mvc.perform(post("/api/conversations").contentType("application/json").content("{\"requestId\":\"r\"," +
                            "\"title\":\"other\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(post("/api/conversations").contentType("application/json").content("{\"requestId\":\"deleted" +
                            "\"}"))
                    .andExpect(status().isNotFound());
            for (String field : new String[]{"userId", "messages", "role", "sources", "assistantText"}) {
                mvc.perform(post("/api/conversations").contentType("application/json").content("{\"requestId\":\"r\"," +
                                "\"" + field + "\":\"secret\"}"))
                        .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
            }
            mvc.perform(delete("/api/conversations/9")).andExpect(status().isNoContent());
            mvc.perform(get("/api/conversations/9")).andExpect(status().isNotFound());
            mvc.perform(delete("/api/conversations/10")).andExpect(status().isNotFound());
        }
    }
}


package com.zhishiku.kb.app.document.changenote;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteException;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteReadService;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteWriteService;
import com.zhishiku.kb.app.document.changenote.web.ChangeNoteAdvice;
import com.zhishiku.kb.app.document.changenote.web.ChangeNoteController;
import com.zhishiku.kb.app.document.changenote.web.ChangeNoteResponseFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChangeNoteControllerTest {
    private static final String PATH = "/api/documents/10/versions/3/change-note";
    private static final String INPUT = "{\"text\":\"private-text\",\"reason\":\"private-reason\",\"expectedNoteVersion\":0}";

    @Test
    void everyReasonHasCorrectStatusNoStoreAndNoTextEcho() throws Exception {
        var writes = mock(ChangeNoteWriteService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ChangeNoteController(mock(ChangeNoteReadService.class), writes))
                .setControllerAdvice(new ChangeNoteAdvice()).addFilters(new ChangeNoteResponseFilter()).build();
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            for (var reason : ChangeNoteException.Reason.values()) {
                doThrow(new ChangeNoteException(reason)).when(writes).update(eq(7L), eq(10L), eq(3L), any(), eq("request"));
                var result = mvc.perform(put(PATH).header("X-Request-Id", "request").content(INPUT))
                        .andExpect(status().is(reason.status())).andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.reasonCode").value(reason.name())).andReturn();
                assertThat(result.getResponse().getContentAsString()).doesNotContain("private-text", "private-reason");
            }
        }
    }

    @Test
    void historyPathStrictQueriesAndRequestIdValidation() throws Exception {
        var reads = mock(ChangeNoteReadService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ChangeNoteController(reads, mock(ChangeNoteWriteService.class)))
                .setControllerAdvice(new ChangeNoteAdvice()).addFilters(new ChangeNoteResponseFilter()).build();
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(get(PATH + "/history")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
            verify(reads).history(7, 10, 3, 20, null);
            mvc.perform(get(PATH)).andExpect(status().isOk());
            mvc.perform(get(PATH).param("unknown", "1")).andExpect(status().isBadRequest());
            mvc.perform(get(PATH + "/history").param("limit", "1", "2")).andExpect(status().isBadRequest());
            for (String id : List.of("", " ", "\u0001", "x".repeat(129))) {
                mvc.perform(put(PATH).header("X-Request-Id", id).content(INPUT)).andExpect(status().isBadRequest());
            }
            mvc.perform(put(PATH).content(INPUT)).andExpect(status().isBadRequest());
            mvc.perform(put(PATH).header("X-Request-Id", "request").content(new byte[32769])).andExpect(status().isPayloadTooLarge());
        }
    }
}

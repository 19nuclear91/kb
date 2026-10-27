package com.zhishiku.kb.app.config;

import com.zhishiku.kb.common.api.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiHttpContractTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @RestController
    static class Probe {
        @GetMapping("/probe/{status}")
        String error(@PathVariable int status) {
            throw switch (status) {
                case 400 -> ApiException.badRequest("无效参数");
                case 403 -> ApiException.forbidden("没有权限");
                case 404 -> ApiException.notFound("文档不存在或无权访问");
                case 409 -> ApiException.conflict("版本冲突");
                default -> new ApiException("内部业务失败");
            };
        }

        @PostMapping(value = "/probe", consumes = "application/json")
        String body(@Valid @RequestBody Input input) {
            return input.name();
        }
    }

    /**
     * 用于验证请求参数校验的测试输入。
     *
     * @param name 名称
     */
    record Input(@NotBlank String name) {
    }

    @Test
    void businessErrorsKeepTheirHttpStatus() throws Exception {
        for (int status : new int[]{400, 403, 404, 409, 500})
            mvc.perform(get("/probe/" + status)).andExpect(status().is(status));
    }

    @Test
    void invalidRequestsReturnClientErrorsWithoutEchoingPayloads() throws Exception {
        mvc.perform(get("/probe/not-an-integer")).andExpect(status().isBadRequest());
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{broken-secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求参数或请求体无效"));
        mvc.perform(post("/probe").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("secret"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(put("/probe")).andExpect(status().isMethodNotAllowed());
    }
}


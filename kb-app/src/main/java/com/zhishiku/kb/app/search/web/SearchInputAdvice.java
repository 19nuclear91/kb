package com.zhishiku.kb.app.search.web;

import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.model.SearchFailure;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/** 在 JSON 反序列化前读取至多上限加一个字节，不依赖长度头。 */
@RestControllerAdvice(assignableTypes = SearchController.class)
public class SearchInputAdvice extends RequestBodyAdviceAdapter {
    private final SearchLimitsProperties limits;

    public SearchInputAdvice(SearchLimitsProperties limits) {
        this.limits = limits;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type type,
                            Class<? extends HttpMessageConverter<?>> converter) {
        return type == SearchRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter, Type type,
                                           Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        int maximum = limits.maxRequestBytes();
        if (input.getHeaders().getContentLength() > maximum) {
            throw new SearchFailure(SearchFailure.Kind.REQUEST_TOO_LARGE);
        }
        byte[] bytes = input.getBody().readNBytes(maximum + 1);
        if (bytes.length > maximum) {
            throw new SearchFailure(SearchFailure.Kind.REQUEST_TOO_LARGE);
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }
}

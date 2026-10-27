package com.zhishiku.kb.app.auth.sso;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.AuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 浏览器登录接口；局部解析禁止未知字段及重复 JSON 键。
 */
@RestController
@RequestMapping("/api/auth/sso")
public class SsoController {
    private static final ObjectMapper JSON =
            new ObjectMapper(JsonFactory.builder().build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final SsoSettings settings;
    private final SsoFacade facade;
    private final SsoCookies cookies;

    public SsoController(SsoSettings settings, SsoFacade facade, SsoCookies cookies) {
        this.settings = settings;
        this.facade = facade;
        this.cookies = cookies;
    }

    @GetMapping("/providers")
    public R<List<SsoViews.Provider>> providers(HttpServletRequest request, HttpServletResponse response) {
        headers(response);
        noQuery(request);
        return R.ok(settings.enabled().stream().map(id -> new SsoViews.Provider(id, id)).toList());
    }

    @GetMapping("/bindings")
    public R<List<SsoViews.BindingStatus>> bindings(HttpServletRequest request, HttpServletResponse response) {
        headers(response);
        noQuery(request);
        return R.ok(facade.statuses(token(request)));
    }

    @PostMapping("/{id}/login")
    public R<SsoViews.Authorization> login(@PathVariable String id, HttpServletRequest request,
                                           HttpServletResponse response) {
        return start(id, false, request, response);
    }

    @PostMapping("/{id}/bindings")
    public R<SsoViews.Authorization> bind(@PathVariable String id, HttpServletRequest request,
                                          HttpServletResponse response) {
        return start(id, true, request, response);
    }

    private R<SsoViews.Authorization> start(String id, boolean bind, HttpServletRequest request,
                                            HttpServletResponse response) {
        headers(response);
        post(request);
        settings.connection(id);
        JsonNode body = body(request, bind ? Set.of("password") : Set.of());
        String password = null;
        if (bind) {
            JsonNode field = body.get("password");
            if (field != null && !field.isNull() && !field.isTextual()) throw SsoException.invalid();
            password = field == null || field.isNull() ? null : field.textValue();
        }
        var started = facade.start(id, cookies.read(request, cookies.browserName()), password, token(request), bind);
        String authorization = facade.authorization(started);
        cookies.write(response, cookies.browserName(), started.browser(), 600);
        return R.ok(new SsoViews.Authorization(authorization));
    }

    @GetMapping("/{id}/callback")
    public void callback(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        headers(response);
        settings.connection(id);
        String codeKey = id.equals("dingtalk") ? "authCode" : "code";
        Set<String> allowed = Set.of("state", codeKey, "error", "error_description");
        Map<String, String> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (!allowed.contains(key) || values.length != 1 || values[0].length() > 2048) throw SsoException.invalid();
            params.put(key, values[0]);
        });
        if (params.containsKey(codeKey) == params.containsKey("error") || (!params.containsKey("error") && params.containsKey("error_description")))
            throw SsoException.invalid();
        var result = facade.callback(id, cookies.read(request, cookies.browserName()), params);
        cookies.write(response, cookies.resultName(result.flowId()), result.value(), result.ttlSeconds());
        response.setStatus(303);
        response.setHeader("Location", settings.properties().getResultPageUri());
    }

    @PostMapping("/exchange")
    public R<Object> exchange(HttpServletRequest request, HttpServletResponse response) {
        headers(response);
        post(request);
        body(request, Set.of());
        String browser = cookies.read(request, cookies.browserName());
        SsoFlow f = facade.current(browser);
        String resultName = cookies.resultName(f.getFlowId());
        Object result = facade.exchange(f, browser, cookies.read(request, resultName), token(request));
        cookies.write(response, resultName, "", 0);
        return R.ok(result);
    }

    private void post(HttpServletRequest request) {
        noQuery(request);
        List<String> origins = Collections.list(request.getHeaders("Origin"));
        if (origins.size() != 1) throw new SsoException(403, "INVALID_FLOW");
        settings.origin(origins.getFirst());
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType());
            if (!type.getType().equals("application") || !type.getSubtype().equals("json"))
                throw SsoException.invalid();
        } catch (IllegalArgumentException invalid) {
            throw SsoException.invalid();
        }
    }

    private static JsonNode body(HttpServletRequest request, Set<String> fields) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(8193);
            if (bytes.length > 8192) throw SsoException.invalid();
            try (var parser = JSON.createParser(bytes)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || !node.isObject() || parser.nextToken() != null) throw SsoException.invalid();
                var names = node.fieldNames();
                while (names.hasNext()) if (!fields.contains(names.next())) throw SsoException.invalid();
                return node;
            }
        } catch (Exception invalid) {
            throw SsoException.invalid();
        }
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()) throw SsoException.invalid();
        if (!request.getParameterMap().isEmpty()) throw SsoException.invalid();
    }

    private static String token(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(AuthHeaders.SA_TOKEN));
        if (values.size() > 1) throw SsoException.invalid();
        return values.isEmpty() ? null : values.getFirst();
    }

    public static void headers(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    @ExceptionHandler(SsoException.class)
    public ResponseEntity<R<SsoViews.Error>> error(SsoException error, HttpServletResponse response) {
        headers(response);
        return ResponseEntity.status(error.status()).body(new R<>(500, "企业认证请求未完成", new SsoViews.Error(error.code(),
                UUID.randomUUID().toString())));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<R<SsoViews.Error>> unavailable(HttpServletResponse response) {
        return error(SsoException.storage(), response);
    }
}

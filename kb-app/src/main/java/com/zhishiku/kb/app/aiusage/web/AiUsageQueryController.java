package com.zhishiku.kb.app.aiusage.web;

import com.zhishiku.kb.app.aiusage.service.AiUsageReadService;
import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.common.api.R;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 当前独立资格由查询服务逐次验证，统一错误和 no-store 由查询 advice 提供。
 */
@RestController
@AdminQueryEndpoint
@RequestMapping("/api/admin/ai")
public class AiUsageQueryController {
    private final AiUsageReadService service;
    public AiUsageQueryController(AiUsageReadService service) { this.service = service; }

    @GetMapping("/usage")
    public R<Map<String, Object>> usage(@RequestParam MultiValueMap<String, String> parameters) { return R.ok(service.usage(parameters, false)); }

    @GetMapping("/usage/groups")
    public R<Map<String, Object>> groups(@RequestParam MultiValueMap<String, String> parameters) { return R.ok(service.usage(parameters, true)); }

    @GetMapping("/audits")
    public R<Map<String, Object>> audits(@RequestParam MultiValueMap<String, String> parameters) { return R.ok(service.audits(parameters)); }

    @GetMapping("/audits/{eventId}")
    public R<Map<String, Object>> detail(@PathVariable String eventId) { return R.ok(service.detail(eventId)); }
}

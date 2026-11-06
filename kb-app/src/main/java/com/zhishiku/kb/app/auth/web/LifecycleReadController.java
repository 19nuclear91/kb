package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleQueryService;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleViews;
import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.common.api.R;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@AdminQueryEndpoint
@RestController
@RequestMapping("/api/admin/identity")
public class LifecycleReadController {
    private final LifecycleQueryService service;

    public LifecycleReadController(LifecycleQueryService service) {
        this.service = service;
    }

    @GetMapping("/lifecycle/status")
    public R<LifecycleViews.Status> status() {
        return R.ok(service.status(StpUtil.getTokenValue()));
    }

    @GetMapping("/users/{userId}/lifecycle")
    public R<LifecycleViews.User> user(@PathVariable String userId) {
        return R.ok(service.user(StpUtil.getTokenValue(), userId));
    }

    @GetMapping("/lifecycle/events")
    public R<QueryPage<LifecycleViews.Event>> events(@RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.events(StpUtil.getTokenValue(), params));
    }

    @GetMapping("/lifecycle/events/{eventId}/items")
    public R<QueryPage<LifecycleViews.Item>> items(@PathVariable String eventId, @RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.items(StpUtil.getTokenValue(), eventId, params));
    }

    @GetMapping("/lifecycle/emergency")
    public R<QueryPage<LifecycleViews.Emergency>> emergency(@RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.emergency(StpUtil.getTokenValue(), params));
    }
}

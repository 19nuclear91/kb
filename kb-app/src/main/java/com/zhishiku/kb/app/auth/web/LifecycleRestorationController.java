package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleRestorationService;
import com.zhishiku.kb.common.api.R;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/identity/users/{userId}/lifecycle")
public class LifecycleRestorationController {
    private final LifecycleRestorationService service;

    public LifecycleRestorationController(LifecycleRestorationService service) {
        this.service = service;
    }

    @PostMapping("/restoration-check")
    public R<LifecycleRestorationService.Verified> check(@PathVariable long userId, @RequestBody LifecycleRestorationService.Check input) {
        return R.ok(service.check(StpUtil.getTokenValue(), userId, input));
    }

    @PostMapping("/restore")
    public R<LifecycleRestorationService.Restored> restore(@PathVariable long userId, @RequestBody LifecycleRestorationService.Confirmation input) {
        return R.ok(service.restore(StpUtil.getTokenValue(), userId, input));
    }
}

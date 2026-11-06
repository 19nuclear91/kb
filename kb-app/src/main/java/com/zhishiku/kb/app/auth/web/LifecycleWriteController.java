package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleMappingService;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleReception;
import com.zhishiku.kb.app.auth.lifecycle.LifecycleRetryService;
import com.zhishiku.kb.common.api.R;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/identity")
public class LifecycleWriteController {
    private final LifecycleMappingService mappings;
    private final LifecycleRetryService retries;

    public LifecycleWriteController(LifecycleMappingService mappings, LifecycleRetryService retries) {
        this.mappings = mappings;
        this.retries = retries;
    }

    @PostMapping("/users/{userId}/lifecycle/verification")
    public R<LifecycleMappingService.Verified> verify(@PathVariable long userId, @RequestBody LifecycleMappingService.Verification input) {
        return R.ok(mappings.verify(StpUtil.getTokenValue(), userId, input));
    }

    @PutMapping("/users/{userId}/lifecycle/mapping")
    public R<LifecycleMappingService.Mapped> map(@PathVariable long userId, @RequestBody LifecycleMappingService.Confirmation input) {
        return R.ok(mappings.map(StpUtil.getTokenValue(), userId, input));
    }

    @DeleteMapping("/users/{userId}/lifecycle/mapping")
    public R<LifecycleMappingService.Mapped> exit(@PathVariable long userId, @RequestBody LifecycleMappingService.Exit input) {
        return R.ok(mappings.exit(StpUtil.getTokenValue(), userId, input));
    }

    @PostMapping("/lifecycle/events/{eventId}/retry")
    public R<LifecycleReception.Result> retry(@PathVariable long eventId, @RequestBody LifecycleRetryService.Request input) {
        return R.ok(retries.retry(StpUtil.getTokenValue(), eventId, input));
    }
}

package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.lifecycle.EmergencyManagementService;
import com.zhishiku.kb.common.api.R;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/identity/lifecycle/emergency")
public class EmergencyManagementController {
    private final EmergencyManagementService service;

    public EmergencyManagementController(EmergencyManagementService service) {
        this.service = service;
    }

    @PostMapping("/verification")
    public ResponseEntity<R<EmergencyManagementService.Verified>> verify(
            @RequestBody EmergencyManagementService.Verification input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(R.ok(service.verify(StpUtil.getTokenValue(), input)));
    }

    @PostMapping
    public ResponseEntity<R<EmergencyManagementService.Registered>> register(
            @RequestBody EmergencyManagementService.Confirmation input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(R.ok(service.register(StpUtil.getTokenValue(), input)));
    }
}

package com.zhishiku.kb.app.reporting.web;

import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.app.reporting.model.OperationsReport;
import com.zhishiku.kb.app.reporting.service.OperationsReportService;
import com.zhishiku.kb.common.api.R;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AdminQueryEndpoint
@RequestMapping("/api/admin/reports")
public class OperationsReportController {
    private final OperationsReportService service;

    public OperationsReportController(OperationsReportService service) {
        this.service = service;
    }

    @GetMapping("/operations")
    public R<OperationsReport> operations(@RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(service.read(parameters));
    }
}

package com.zhishiku.kb.app.controller.health;

import com.zhishiku.kb.app.domain.vo.health.KnowledgeHealthRadarVo;
import com.zhishiku.kb.app.service.health.HealthReadService;
import com.zhishiku.kb.app.service.impl.health.HealthQueries;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 空间上下文中的只读维护诊断。 */
@RestController
@RequiredArgsConstructor
@Tag(name = "知识健康雷达", description = "当前可管理空间中的六类维护信号与去重文档清单")
public class HealthController {
    private final HealthReadService service;

    @GetMapping("/api/admin/spaces/{spaceId}/health-radar")
    @Operation(summary = "读取单空间健康雷达", description = "要求独立读取权限和空间管理资格，适用期只统计当前可读发布版本")
    @Parameters({
            @Parameter(name = "signal", in = ParameterIn.QUERY, description = "默认ALL；可选STEWARD_UNASSIGNED未指定、STEWARD_NEEDS_REASSIGN需改派、REVIEW_OVERDUE复审逾期、REVIEW_DUE_SOON即将复审、APPLICABILITY_EXPIRED已届满、APPLICABILITY_EXPIRING_SOON即将届满"),
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页文档条数，1至100，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "绑定当前身份、空间、筛选、页大小和业务日期的游标，最多2048字符")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "R包装的同次快照汇总与当前页，所有计数与身份保持十进制精度", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "400", description = "路径、查询参数或游标无效"),
            @ApiResponse(responseCode = "401", description = "当前会话或认证代次无效"),
            @ApiResponse(responseCode = "403", description = "主体不可用、需要改密或缺少独立雷达权限"),
            @ApiResponse(responseCode = "404", description = "空间不存在或不可管理"),
            @ApiResponse(responseCode = "409", description = "空间归档或游标跨业务日期；X-Kb-Health-Error区分SPACE_ARCHIVED和RADAR_DATE_CHANGED"),
            @ApiResponse(responseCode = "503", description = "必要事实、完整结果或读取预算不可确认")
    })
    public ResponseEntity<R<KnowledgeHealthRadarVo>> read(
            @Parameter(name = "spaceId", description = "目标空间ID，正整数，最大9223372036854775807", required = true)
            @PathVariable("spaceId")
            String spaceId,
            HttpServletRequest request
    ) {
        var result = service.read(HealthQueries.id(spaceId), HealthQueries.parse(request.getParameterMap()));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(R.ok(result));
    }
}

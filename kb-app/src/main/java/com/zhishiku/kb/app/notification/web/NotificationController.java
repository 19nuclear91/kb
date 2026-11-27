package com.zhishiku.kb.app.notification.web;

import com.zhishiku.kb.app.notification.model.NotificationViews;
import com.zhishiku.kb.app.notification.service.UserNotificationService;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.NotificationContract;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 本人站内通知；身份只取服务端会话，不提供创建、删除或通用发送接口。 */
@RestController
@Tag(name = "站内通知")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "输入或通知能力合同无效"),
        @ApiResponse(responseCode = "401", description = "会话或认证代次无效"),
        @ApiResponse(responseCode = "403", description = "当前主体不可用或需要强制改密"),
        @ApiResponse(responseCode = "404", description = "本人通知不存在"),
        @ApiResponse(responseCode = "503", description = "通知或授权结果暂不可确认")
})
public class NotificationController {
    private final UserNotificationService service;

    public NotificationController(UserNotificationService service) {
        this.service = service;
    }

    @GetMapping("/api/notifications")
    @Operation(summary = "本人通知列表", description = "仅 limit（1–100，默认 20）与 cursor；按当前权限过滤后分页，无总数")
    @Parameters({
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页1至100条，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "可选绑定本人与合同的下一页游标"),
            @Parameter(name = "X-Kb-Notification-Contract", in = ParameterIn.HEADER,
                    description = "缺省原四类，KNOWLEDGE_GAPS_V1增加问题，KNOWLEDGE_SUBSCRIPTIONS_V1再增加订阅与周报")
    })
    @ApiResponse(responseCode = "200", description = "R包装的本人当前可见通知页", useReturnTypeSchema = true)
    public R<NotificationViews.Page> list(
                                          @Parameter(hidden = true)
                                          @RequestParam
                                          MultiValueMap<String, String> params,
                                          HttpServletRequest request) throws IOException {
        NotificationInput.noBody(request);
        var contract = NotificationInput.contract(request);
        return R.ok(contract == NotificationContract.LEGACY ? service.list(params) : service.list(params, contract));
    }

    @GetMapping("/api/notifications/unread-count")
    @Operation(summary = "本人当前可见未读数", description = "无参数；精确十进制字符串，不等于审核待办数")
    @Parameter(name = "X-Kb-Notification-Contract", in = ParameterIn.HEADER,
            description = "缺省原四类，KNOWLEDGE_GAPS_V1或KNOWLEDGE_SUBSCRIPTIONS_V1；与列表必须一致")
    @ApiResponse(responseCode = "200", description = "R包装的本人当前可见未读数，每份周报最多一条", useReturnTypeSchema = true)
    public R<NotificationViews.UnreadCount> unreadCount(
                                                        @Parameter(hidden = true)
                                                        @RequestParam
                                                        MultiValueMap<String, String> params,
                                                        HttpServletRequest request) throws IOException {
        NotificationInput.noBody(request);
        var contract = NotificationInput.contract(request);
        return R.ok(contract == NotificationContract.LEGACY ? service.unreadCount(params) : service.unreadCount(params, contract));
    }

    @PutMapping("/api/notifications/{notificationId}/read")
    @Operation(summary = "标记本人通知已读", description = "无正文和查询参数；幂等保持首次已读时间，不处理审核")
    @ApiResponse(responseCode = "200", description = "R包装的本人首次已读回执，不返回资源目标", useReturnTypeSchema = true)
    public R<NotificationViews.ReadReceipt> read(
                                                  @Parameter(name = "notificationId", description = "本人通知标准UUID", required = true)
                                                  @PathVariable
                                                  String notificationId,
                                                 HttpServletRequest request) throws IOException {
        NotificationInput.noPayload(request);
        return R.ok(service.markRead(NotificationInput.notificationId(notificationId)));
    }
}

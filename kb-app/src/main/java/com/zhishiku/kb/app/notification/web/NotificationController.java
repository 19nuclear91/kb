package com.zhishiku.kb.app.notification.web;

import com.zhishiku.kb.app.notification.model.NotificationViews;
import com.zhishiku.kb.app.notification.service.UserNotificationService;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.NotificationContract;
import io.swagger.v3.oas.annotations.Operation;
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
public class NotificationController {
    private final UserNotificationService service;

    public NotificationController(UserNotificationService service) {
        this.service = service;
    }

    @GetMapping("/api/notifications")
    @Operation(summary = "本人通知列表", description = "仅 limit（1–100，默认 20）与 cursor；按当前权限过滤后分页，无总数")
    public R<NotificationViews.Page> list(@RequestParam MultiValueMap<String, String> params,
                                          HttpServletRequest request) throws IOException {
        NotificationInput.noBody(request);
        var contract = NotificationInput.contract(request);
        return R.ok(contract == NotificationContract.LEGACY ? service.list(params) : service.list(params, contract));
    }

    @GetMapping("/api/notifications/unread-count")
    @Operation(summary = "本人当前可见未读数", description = "无参数；精确十进制字符串，不等于审核待办数")
    public R<NotificationViews.UnreadCount> unreadCount(@RequestParam MultiValueMap<String, String> params,
                                                        HttpServletRequest request) throws IOException {
        NotificationInput.noBody(request);
        var contract = NotificationInput.contract(request);
        return R.ok(contract == NotificationContract.LEGACY ? service.unreadCount(params) : service.unreadCount(params, contract));
    }

    @PutMapping("/api/notifications/{notificationId}/read")
    @Operation(summary = "标记本人通知已读", description = "无正文和查询参数；幂等保持首次已读时间，不处理审核")
    public R<NotificationViews.ReadReceipt> read(@PathVariable String notificationId,
                                                 HttpServletRequest request) throws IOException {
        NotificationInput.noPayload(request);
        return R.ok(service.markRead(NotificationInput.notificationId(notificationId)));
    }
}

package com.zhishiku.kb.app.conversation.web;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.zhishiku.kb.app.conversation.model.ConversationPage;
import com.zhishiku.kb.app.conversation.model.ConversationView;
import com.zhishiku.kb.app.conversation.model.CreationResult;
import com.zhishiku.kb.app.conversation.model.TurnPage;
import com.zhishiku.kb.app.conversation.model.TurnView;
import com.zhishiku.kb.app.conversation.service.ConversationService;
import com.zhishiku.kb.app.conversation.service.TurnExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 个人私有会话；所有敏感响应禁止缓存。
 */
@RestController
@RequestMapping("/api/conversations")
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class ConversationController {
    private final ConversationService conversations;
    private final TurnExecutionService execution;

    public ConversationController(ConversationService conversations, TurnExecutionService execution) {
        this.conversations = conversations;
        this.execution = execution;
    }

    @Getter
    @Setter
    @Schema(description = "创建本人会话")
    @JsonDeserialize(using = CreateRequestDeserializer.class)
    public static class CreateRequest {
        @Schema(description = "可选标题，缺省为新会话")
        private String title;
        @Schema(description = "1–128 字符的幂等请求标识")
        private String requestId;

        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw ConversationService.invalid();
        }
    }

    @PostMapping
    @Operation(summary = "创建本人会话")
    public ResponseEntity<CreationResult> create(@RequestBody CreateRequest request, @RequestHeader(value = "X" +
            "-Request-Id", required = false) String header) {
        var result = conversations.create(StpUtil.getLoginIdAsLong(), request.getTitle(), request.getRequestId(),
                header);
        return ResponseEntity.status(result.replayed() ? 200 : 201).cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping
    @Operation(summary = "按创建顺序分页本人会话")
    public ResponseEntity<ConversationPage> list(@RequestParam(required = false) Integer limit,
                                                 @RequestParam(required = false) String cursor) {
        return response(conversations.list(StpUtil.getLoginIdAsLong(), limit, cursor));
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取本人会话元数据")
    public ResponseEntity<ConversationView> detail(@PathVariable long id) {
        return response(conversations.detail(StpUtil.getLoginIdAsLong(), id));
    }

    @GetMapping("/{id}/turns")
    @Operation(summary = "按当前权限读取本人历史")
    public ResponseEntity<TurnPage> turns(@PathVariable long id, @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) Long cursor) {
        return response(conversations.turns(StpUtil.getLoginIdAsLong(), id, limit, cursor));
    }

    @GetMapping("/{id}/turns/{turn}")
    @Operation(summary = "按当前权限读取本人单轮")
    public ResponseEntity<TurnView> turn(@PathVariable long id, @PathVariable UUID turn) {
        return response(conversations.turn(StpUtil.getLoginIdAsLong(), id, turn));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除正文并保留最小账本墓碑")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        execution.delete(StpUtil.getLoginIdAsLong(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}


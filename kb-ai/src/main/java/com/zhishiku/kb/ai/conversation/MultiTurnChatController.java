package com.zhishiku.kb.ai.conversation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 首次 SSE 与重放 JSON 的状态与媒体类型在响应提交前决定。
 */
@RestController
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class MultiTurnChatController {
    private final MultiTurnChatService service;
    private final ConversationProperties properties;

    public MultiTurnChatController(MultiTurnChatService service, ConversationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Getter
    @Setter
    @Schema(description = "发起新轮次")
    @JsonDeserialize(using = TurnRequestDeserializer.class)
    public static class TurnRequest {
        @Schema(description = "当前原始问题")
        private String question;
        @Schema(description = "幂等请求标识")
        private String requestId;
        @Schema(description = "客户端已知最后轮次序号")
        private Long expectedLastTurnSeq;

        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw invalid();
        }
    }

    @PostMapping(path = "/api/ai/conversations/{id}/turns", consumes = "application/json")
    @Operation(summary = "多轮问答", description = "首次返回 citation/delta/done/error SSE 和会话、轮次标识头；匹配重放返回 200 JSON 身份与状态。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "首次 SSE 或匹配重放 JSON",
                    headers = {@Header(name = "X-Conversation-Id", description = "会话 ID", schema = @Schema(type =
                            "string")),
                            @Header(name = "X-Turn-Id", description = "轮次 ID", schema = @Schema(type = "string"))},
                    content = {@Content(mediaType = "text/event-stream"),
                            @Content(mediaType = "application/json", schema = @Schema(implementation =
                                    TurnReplay.class))}),
            @ApiResponse(responseCode = "400", description = "输入或预算无效"),
            @ApiResponse(responseCode = "401", description = "会话失效"),
            @ApiResponse(responseCode = "403", description = "主体停用或必须改密"),
            @ApiResponse(responseCode = "404", description = "会话不存在、已删除或不属于本人"),
            @ApiResponse(responseCode = "409", description = "请求绑定、轮次序号或活动资格冲突"),
            @ApiResponse(responseCode = "429", description = "当前部门配额不足"),
            @ApiResponse(responseCode = "503", description = "模型、持久化或配额能力不可用")})
    public Mono<ResponseEntity<?>> turn(@PathVariable long id, @RequestBody TurnRequest request,
                                        @Parameter(hidden = true) @RequestHeader(value = "satoken", required = false) String token,
                                        @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return Mono.defer(() -> {
            if (id <= 0 || request.getQuestion() == null || request.getQuestion().isBlank()
                    || request.getQuestion().codePointCount(0, request.getQuestion().length()) > properties.getMaxQuestionCodepoints()
                    || !MultiTurnChatService.unicode(request.getQuestion())
                    || request.getExpectedLastTurnSeq() == null || request.getExpectedLastTurnSeq() < 0)
                throw invalid();
            String body = request.getRequestId() == null ? null : request.getRequestId().strip(), head =
                    header == null ? null : header.strip();
            if (body != null && head != null && !body.equals(head)) throw invalid();
            String chosen = body == null ? head : body;
            if (chosen == null || chosen.isEmpty() || chosen.codePointCount(0, chosen.length()) > 128
                    || !MultiTurnChatService.unicode(chosen) || chosen.chars().anyMatch(Character::isISOControl))
                throw invalid();
            return service.prepare(id, request.getQuestion(), chosen, request.getExpectedLastTurnSeq(), token).map(prepared -> {
                var result = prepared.result();
                var response = ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Conversation-Id",
                                Long.toString(result.conversationId()))
                        .header("X-Turn-Id", result.turnId().toString());
                if (result.replayed()) return response.contentType(MediaType.APPLICATION_JSON)
                        .body(new TurnReplay(result.conversationId(), result.turnId(), result.turnSeq(),
                                result.status(), true));
                return response.contentType(MediaType.TEXT_EVENT_STREAM).body(service.stream(prepared));
            });
        });
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "多轮请求参数无效");
    }
}


package com.zhishiku.kb.app.parse.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 脱敏的解析死信事实；不包含对象 key、正文或完整堆栈。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "ParseDeadLetter", description = "解析任务死信摘要")
public class ParseDeadLetterView {
    @Schema(description = "死信内部 ID", example = "9001")
    private Long id;
    @Schema(description = "解析任务 ID", example = "3001")
    private Long taskId;
    @Schema(description = "文档版本 ID", example = "2001")
    private Long versionId;
    @Schema(description = "执行周期", example = "2")
    private Long executionGeneration;
    @Schema(description = "已尝试次数", example = "3")
    private Integer attempts;
    @Schema(description = "失败分类", example = "PERMANENT")
    private String failureClass;
    @Schema(description = "稳定失败代码", example = "PARSER_ERROR")
    private String failureCode;
    @Schema(description = "脱敏错误摘要")
    private String errorSummary;
    @Schema(description = "死信状态", example = "OPEN")
    private String status;
    @Schema(description = "进入死信时间")
    private OffsetDateTime enteredAt;
    @Schema(description = "解决时间；未解决时为空")
    private OffsetDateTime resolvedAt;
}

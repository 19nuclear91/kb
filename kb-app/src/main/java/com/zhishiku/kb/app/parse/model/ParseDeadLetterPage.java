package com.zhishiku.kb.app.parse.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "ParseDeadLetterPage", description = "解析死信分页结果")
public class ParseDeadLetterPage {
    @Schema(description = "当前页，从 1 开始", example = "1")
    private int page;
    @Schema(description = "每页条数", example = "20")
    private int size;
    @Schema(description = "符合筛选条件的总条数", example = "42")
    private long total;
    @Schema(description = "脱敏死信条目")
    private List<ParseDeadLetterView> items;
}

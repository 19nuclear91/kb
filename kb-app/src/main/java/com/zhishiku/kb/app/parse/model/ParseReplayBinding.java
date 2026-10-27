package com.zhishiku.kb.app.parse.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 仅供服务端核对的重放绑定，不作为 HTTP 回执返回。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParseReplayBinding {
    private Long deadLetterId;
    private Long actorUserId;
    private String reason;
}

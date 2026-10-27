package com.zhishiku.kb.app.space.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前调用者可上传空间的最小投影，不附带空间 ACL 或正文资格。
 *
 * @param spaceId 空间标识
 * @param name 空间显示名
 */
@Schema(description = "可上传空间候选")
public record UploadSpaceCandidate(long spaceId, String name) {
}

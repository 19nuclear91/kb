package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前用户可查看的指定版本引用，不包含对象存储定位信息。
 *
 * @param status       当前查询的分块可用性，不表示永久删除
 * @param docId        文档 ID
 * @param docTitle     当前文档标题，不是历史标题快照
 * @param versionId    引用指定的版本 ID
 * @param versionNo    指定版本的展示编号
 * @param chunkId      请求的分块 ID；无匹配时仅回显，不证明曾经存在
 * @param chunk        完整存储分块；无匹配时为 {@code null}
 * @param previewPath  原版本预览业务路径，访问时重新鉴权及检查格式
 * @param downloadPath 原版本下载业务路径；没有独立下载权限时为 {@code null}
 */
@Schema(description = "指定版本引用的核对结果，仅在原版本具有当前 view 权限时返回")
public record DocumentCitationView(
        @Schema(description = "片段可用性；无匹配不表示永久删除") Status status,
        @Schema(description = "文档 ID") Long docId,
        @Schema(description = "当前文档标题，不是历史标题快照") String docTitle,
        @Schema(description = "引用指定的版本 ID") Long versionId,
        @Schema(description = "指定版本的展示编号，不用于定位") Integer versionNo,
        @Schema(description = "请求的分块 ID；无匹配时仅回显") Long chunkId,
        @Schema(description = "完整存储分块；无匹配时为空") Chunk chunk,
        @Schema(description = "固定版本的预览路径，仍需鉴权且可能不支持预览格式") String previewPath,
        @Schema(description = "固定版本的下载路径；没有 download 权限时为空") String downloadPath) {

    /**
     * 仅描述本次查询是否存在匹配片段。
     */
    @Schema(description = "AVAILABLE 为存在匹配片段，CHUNK_UNAVAILABLE 为当前无匹配片段")
    public enum Status {
        AVAILABLE,
        CHUNK_UNAVAILABLE
    }

    /**
     * 完整存储正文及解析器来源提示，不是当轮 prompt 快照。
     *
     * @param chunkIdx        分块序号，不用于替代分块 ID
     * @param content         完整存储正文
     * @param pageNo          首个来源块页码，未知时为空
     * @param headingPath     解析器报告的标题路径
     * @param structureType   解析器报告的结构类型
     * @param sourceLineStart 来源块起始行提示
     * @param sourceLineEnd   来源块结束行提示
     * @param sourceCharStart 来源块起始字符提示，不是原件精确坐标
     * @param sourceCharEnd   来源块结束字符提示，不是原件精确坐标
     * @param sourceLocator   解析器提供的来源标识，仅作为数据
     */
    @Schema(description = "完整存储分块和非精确来源提示，不是当轮 prompt 快照")
    public record Chunk(
            @Schema(description = "分块序号，不用于替代分块 ID") Integer chunkIdx,
            @Schema(description = "完整存储正文") String content,
            @Schema(description = "首个来源块页码，未知时为空") Integer pageNo,
            @Schema(description = "解析器报告的标题路径") String headingPath,
            @Schema(description = "解析器报告的结构类型") String structureType,
            @Schema(description = "来源块起始行提示") Integer sourceLineStart,
            @Schema(description = "来源块结束行提示") Integer sourceLineEnd,
            @Schema(description = "来源起始字符提示，不承诺原件精确坐标") Integer sourceCharStart,
            @Schema(description = "来源结束字符提示，不承诺原件精确坐标") Integer sourceCharEnd,
            @Schema(description = "解析器提供的来源标识，仅作为数据") String sourceLocator) {
    }
}

package com.zhishiku.kb.app.search.model;

import com.zhishiku.kb.app.search.expansion.SearchQueryPlan;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 有界编码器产生的辅助检索 JSON 公开模型，用于准确描述字节响应。 */
public final class AssistedSearchViews {
    private AssistedSearchViews() {
    }

    @Schema(name = "AssistedSearchResponse", description = "R 封装的辅助检索成功结果，完整编码后一次发送")
    public record Response(
            @Schema(description = "成功业务码，固定 200")
            int code,
            @Schema(description = "成功提示，固定 ok")
            String message,
            @Schema(description = "授权命中及本次词典辅助说明")
            Data data
    ) {
    }

    @Schema(name = "AssistedSearchData", description = "辅助检索数据；无可读文档时 hits 为空，辅助说明仍独立返回")
    public record Data(
            @Schema(description = "按本次排名排列的授权文档分块，最多 topK 条")
            List<Hit> hits,
            @Schema(description = "原文、有效查询、采用的词条及待选择建议")
            SearchQueryPlan.Assistance assistance
    ) {
    }

    @Schema(name = "AssistedSearchHit", description = "辅助检索命中分块；业务标识与版本号按精确十进制字符串输出")
    public record Hit(
            @Schema(description = "命中分块 ID，精确正整数字符串")
            String chunkId,
            @Schema(description = "所属文档 ID，精确正整数字符串")
            String docId,
            @Schema(description = "分块所属版本 ID，精确正整数字符串")
            String versionId,
            @Schema(description = "版本展示编号，正整数字符串，不用于定位")
            String versionNo,
            @Schema(description = "已授权可读的文档标题")
            String docTitle,
            @Schema(description = "所属知识空间 ID，精确正整数字符串")
            String spaceId,
            @Schema(description = "文档密级编号，含义遵循当前密级字典")
            Short securityLevel,
            @Schema(description = "文档内分块序号，从 0 开始")
            Integer chunkIdx,
            @Schema(description = "已授权可读的分块正文")
            String content,
            @Schema(description = "首个来源块页码，未知时为 null，不表示整块页范围", nullable = true)
            Integer pageNo,
            @Schema(description = "解析器报告的章节标题路径，未知时为 null", nullable = true)
            String headingPath,
            @Schema(description = "解析器报告的结构类别，如 PARAGRAPH；未知时为 null", nullable = true)
            String structureType,
            @Schema(description = "来源起始行号，仅作核对提示，未知时为 null", nullable = true)
            Integer sourceLineStart,
            @Schema(description = "来源结束行号，仅作核对提示，未知时为 null", nullable = true)
            Integer sourceLineEnd,
            @Schema(description = "来源起始字符位置，不保证原件精确坐标，未知时为 null", nullable = true)
            Integer sourceCharStart,
            @Schema(description = "来源结束字符位置，不保证原件精确坐标，未知时为 null", nullable = true)
            Integer sourceCharEnd,
            @Schema(description = "解析器提供的来源定位标识，未知时为 null", nullable = true)
            String sourceLocator,
            @Schema(description = "本次检索排名得分，仅在同次查询内可比，不是相似度百分比")
            Double score
    ) {
    }
}

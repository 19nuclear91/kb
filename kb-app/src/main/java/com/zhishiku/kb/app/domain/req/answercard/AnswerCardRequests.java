package com.zhishiku.kb.app.domain.req.answercard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 严格解码合同；写入字段均必传，只有标注可空的字段接受JSON null。 */
public final class AnswerCardRequests {
    private AnswerCardRequests() {
    }

    @Schema(description = "固定依据，最多十份不同文档，必须明确选择当前发布事实")
    public record Source(
            @Schema(description = "文档正整数ID，规范十进制字符串")
            String documentId,
            @Schema(description = "当前发布版本正整数ID，规范十进制字符串")
            String versionId,
            @Schema(description = "当前日期声明修订号，非负十进制字符串，未声明行为0")
            String applicabilityVersion
    ) {
    }

    @Schema(description = "卡片日历声明，三个字段必传，不自动补长期有效")
    public record Dates(
            @Schema(description = "生效日，1900-01-01至2999-12-31的YYYY-MM-DD，含当天；可为null表示未知")
            String effectiveFrom,
            @Schema(description = "UNDECLARED未知、OPEN_ENDED长期有效、DATE指定截止日")
            String untilKind,
            @Schema(description = "含当天的截止日，格式同生效日；DATE必填，其他类型必须null")
            String effectiveUntil
    ) {
    }

    @Schema(description = "完整草稿正文，每次保存追加修订；所有字段必传")
    public record Content(
            @Schema(description = "标准问题，去首尾空白后1至256个Unicode码点，纯文本")
            String question,
            @Schema(description = "常见问法0至20条，每条1至256码点，归一后不重复或等于标准问题")
            List<String> aliases,
            @Schema(description = "答案纯文本，0至16000码点，送审时不得空白")
            String answer,
            @Schema(description = "适用范围纯文本，0至2000码点，送审时不得空白")
            String applicabilityText,
            @Schema(description = "需独立审核的日历适用声明")
            Dates dates,
            @Schema(description = "0至10份不同文档的固定依据；送审至少一份")
            List<Source> sources
    ) {
    }

    @Schema(description = "创建卡片及第一份草稿，不接受客户端发布或人员身份")
    public record Create(
            @Schema(description = "创建后不可更改的所属空间正整数ID字符串")
            String spaceId,
            @Schema(description = "完整草稿")
            Content content
    ) {
    }

    @Schema(description = "保存完整草稿，所有字段必传")
    public record Save(
            @Schema(description = "原卡片所属空间ID字符串")
            String spaceId,
            @Schema(description = "当前卡片非负版本字符串")
            String expectedCardVersion,
            @Schema(description = "当前分支ID，无活动分支时必须null")
            String branchId,
            @Schema(description = "编辑所基于的活动草稿修订ID，无草稿时为当前发布修订ID")
            String revisionId,
            @Schema(description = "完整新内容，不能用旧来源选择隐式绑定新版")
            Content content
    ) {
    }

    @Schema(description = "送审精确草稿，所有字段必传")
    public record Submit(
            @Schema(description = "卡片空间ID字符串")
            String spaceId,
            @Schema(description = "当前卡片版本字符串")
            String expectedCardVersion,
            @Schema(description = "当前活动分支ID字符串")
            String branchId,
            @Schema(description = "当前分支最新修订ID字符串")
            String revisionId
    ) {
    }

    @Schema(description = "批准或撤回精确轮次，所有字段必传")
    public record Decision(
            @Schema(description = "卡片空间ID字符串")
            String spaceId,
            @Schema(description = "当前卡片版本字符串")
            String expectedCardVersion,
            @Schema(description = "原送审分支ID字符串")
            String branchId,
            @Schema(description = "原送审修订ID字符串")
            String revisionId,
            @Schema(description = "当前审核非负版本字符串")
            String expectedReviewVersion
    ) {
    }

    @Schema(description = "驳回精确审核轮次，所有字段必传")
    public record Rejection(
            @Schema(description = "卡片空间ID字符串")
            String spaceId,
            @Schema(description = "当前卡片版本字符串")
            String expectedCardVersion,
            @Schema(description = "原送审分支ID字符串")
            String branchId,
            @Schema(description = "原送审修订ID字符串")
            String revisionId,
            @Schema(description = "当前审核非负版本字符串")
            String expectedReviewVersion,
            @Schema(description = "私有驳回原因，1至2000个Unicode码点，纯文本")
            String reason
    ) {
    }

    @Schema(description = "空间管理员指定责任人或周期，所有字段必传，改派不重置日期")
    public record Stewardship(
            @Schema(description = "所属空间ID字符串")
            String spaceId,
            @Schema(description = "当前卡片版本字符串")
            String expectedCardVersion,
            @Schema(description = "当前复审版本字符串，首次为0")
            String expectedStewardshipVersion,
            @Schema(description = "当前合格责任人ID字符串，不接受null")
            String responsibleUserId,
            @Schema(description = "月数JSON整数，只能为3、6、12、24")
            int cycleMonths
    ) {
    }

    @Schema(description = "本人确认当前已发布修订仍准确，所有字段必传")
    public record Confirmation(
            @Schema(description = "所属空间ID字符串")
            String spaceId,
            @Schema(description = "当前卡片版本字符串")
            String expectedCardVersion,
            @Schema(description = "当前发布修订ID字符串")
            String revisionId,
            @Schema(description = "当前复审版本字符串")
            String expectedStewardshipVersion
    ) {
    }

    @Schema(description = "独立标准答案搜索；仅接受这三个字段")
    public record Search(
            @Schema(description = "必填原问题，去首尾空白后1至256码点，不接受null")
            String query,
            @Schema(description = "限定所属空间正整数ID字符串，省略或null为不限")
            String spaceId,
            @Schema(description = "返回上限，JSON整数1至20，省略默认10，禁止null")
            Integer limit
    ) {
    }

    @Schema(description = "受控来源名称查询，spaceId与name必传")
    public record SourceOptions(
            @Schema(description = "具有维护资格的目标卡片空间ID字符串")
            String spaceId,
            @Schema(description = "文档标题字面条件，0至128码点，空串允许")
            String name,
            @Schema(description = "JSON整数1至100，省略默认20，禁止null")
            Integer limit,
            @Schema(description = "本用途原游标，省略或null为首页，最长4096字符")
            String cursor
    ) {
    }

    @Schema(description = "责任人候选，只返回合格人员最小身份")
    public record Candidates(
            @Schema(description = "必填显示名字面条件，0至64码点，空串允许")
            String name,
            @Schema(description = "JSON整数1至100，省略默认20，禁止null")
            Integer limit,
            @Schema(description = "当前用途游标，省略或null为首页，最长4096字符")
            String cursor
    ) {
    }

    @Schema(description = "本人原命令核对，所有字段必传；payload字段随原操作严格解码")
    public record CommandQuery(
            @Schema(description = "原操作枚举：ANSWER_CARD_CREATE、SAVE、SUBMIT、APPROVE、REJECT、WITHDRAW、STEWARDSHIP_UPDATE、CONFIRM，均带ANSWER_CARD_前缀")
            String operation,
            @Schema(description = "原X-Request-Id，1至128个非控制Unicode码点")
            String requestId,
            @Schema(description = "原目标卡片ID字符串，创建未知时必须null")
            String cardId,
            @Schema(description = "原轮次ID字符串，仅批准、驳回、撤回有值，其他必须null")
            String reviewId,
            @Schema(description = "原请求正文；结构由operation选择对应具名请求，禁止额外字段",
                    oneOf = {Create.class, Save.class, Submit.class, Decision.class, Rejection.class, Stewardship.class, Confirmation.class})
            com.fasterxml.jackson.databind.JsonNode payload
    ) {
    }
}

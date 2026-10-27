package com.zhishiku.kb.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 文档搬迁回执。
 *
 * <p>两个数字容易被当成失败信号，实际都不是：
 * <ul>
 *   <li>{@code clearedExceptionCount} 是<b>被清除</b>的例外条数。搬迁让例外的鉴权依据
 *       （文档当前所属空间的管理资格）整体失效，所以清除是搬迁的一部分，不是副作用。
 *       这些授权<b>不可恢复</b>，要在新空间保留同等可见性，必须由目标空间的管理员
 *       重新授出——那个决定权正是清除要交还的东西。</li>
 *   <li>{@code clearanceBlockedPrincipalCount} 是目标空间里因<b>密级</b>而当下看不到这篇
 *       文档的主体数。搬迁不改密级，所以它不为 0 很正常，不代表搬迁没生效。口径与
 *       空间/文档授权接口的同名字段完全一致，包括"混合密级的部门不计入受阻"。</li>
 * </ul>
 *
 * <p>{@code replayed} 为真表示这是同一 {@code requestId} 的重复提交，返回的是首次搬迁
 * 的结果：归属没有再次改变，例外也没有再次被清除——后者尤其重要，否则一次重投会
 * 清掉目标空间管理员在搬迁之后刚授出的例外。
 *
 * @param documentId                     被搬迁的文档
 * @param fromSpaceId                    搬迁前的实际所属空间（服务端解析值，不是请求声明值）
 * @param toSpaceId                      搬迁后的所属空间
 * @param clearedExceptionCount          被清除的文档级例外条数
 * @param clearedExceptions              被清除例外的摘要，形如 {@code "3:5:view"}（主体键:权限位）
 * @param clearedExceptionsTruncated     摘要是否因超过上限而被截断
 * @param clearanceBlockedPrincipalCount 目标空间中因密级而当前看不到该文档的主体数
 * @param replayed                       是否为同一请求标识的重放
 */
@Schema(name = "DocumentRelocationResult", description = "文档搬迁回执")
public record DocumentRelocationResult(
        @Schema(description = "被搬迁的文档 ID", example = "10")
        long documentId,
        @Schema(description = "搬迁前的实际所属空间", example = "1")
        long fromSpaceId,
        @Schema(description = "搬迁后的所属空间", example = "2")
        long toSpaceId,
        @Schema(description = "被清除的文档级例外条数；这些授权不可恢复，需在新空间重新授出",
                example = "2")
        int clearedExceptionCount,
        @Schema(description = "被清除例外的摘要，形如 主体键:权限位")
        List<String> clearedExceptions,
        @Schema(description = "摘要是否因超过上限被截断", example = "false")
        boolean clearedExceptionsTruncated,
        @Schema(description = "目标空间中因密级而当前看不到该文档的主体数；不为 0 不代表搬迁失败",
                example = "1")
        long clearanceBlockedPrincipalCount,
        @Schema(description = "是否为同一请求标识的重放", example = "false")
        boolean replayed) {
}

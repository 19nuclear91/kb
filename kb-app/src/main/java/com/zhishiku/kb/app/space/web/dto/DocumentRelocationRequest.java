package com.zhishiku.kb.app.space.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

/**
 * 文档搬迁请求。
 *
 * <p><b>为什么必须写 {@code fromSpaceId}</b>：搬迁的全部鉴权压在源空间上。若文档已被
 * 他人从 A 搬到 C，一个带着"我以为它在 A"的请求打进来，服务端会去校验 manage(C)；
 * 调用方若恰好也管 C，这次校验会通过——于是执行了一次他从未打算做的搬迁，并清掉了
 * 一批他从未看见的例外。把赌注写明，服务端才有据可拒。
 *
 * <p>与空间接口的 {@code version} 约定不同是有意的：空间上的 {@code version} 只能告诉
 * 调用方"有人动过"，而搬迁需要知道"动成了什么"。
 *
 * @param fromSpaceId   调用方认为该文档当前所在的空间；与实际归属不一致即返回冲突
 * @param toSpaceId     目标空间
 * @param requestId     请求幂等标识；缺失时回退到请求头 {@code X-Request-Id}
 * @param securityLevel 只为能<b>显式拒绝</b>而接收，任何非空值都会被拒
 */
@Schema(description = "文档搬迁请求")
public record DocumentRelocationRequest(
        @Schema(description = "调用方认为该文档当前所在的空间；与实际归属不符返回 409",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        @Positive long fromSpaceId,
        @Schema(description = "目标空间；不能是已归档空间，也不能等于当前归属",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
        @Positive long toSpaceId,
        @Schema(description = "请求标识，用于幂等去重；缺失时回退到请求头 X-Request-Id")
        String requestId,
        @Schema(description = "不接受：搬迁不修改密级，密级调整是独立的受保护操作。"
                + "本字段只为把夹带密级的请求显式拒掉而存在")
        Short securityLevel) {
}

package com.zhishiku.kb.ai.chat;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 流式对话事件。
 *
 * <p>SSE 的 {@code event} 字段用来区分类型，前端按类型分别处理。
 * 约定四种：
 *
 * <ul>
 * <li>{@code delta}    —— 增量文本，前端追加显示
 * <li>{@code citation} —— 本轮预算内参考来源，当前编排在增量文本之前发送
 * <li>{@code done}     —— 正常结束，携带统计信息
 * <li>{@code error}    —— 出错结束
 * </ul>
 *
 * <p><b>为什么不用 {@code R} 统一响应包装</b>：{@code R} 是给"一次请求一个完整响应"
 * 的接口用的。流式场景下每个事件都是独立帧，套一层 code/message 只会让前端
 * 每收一个字都要拆一遍壳；错误也不是靠 HTTP 状态码表达的——
 * 连接已经 200 了，中途失败只能靠 {@code error} 事件传递。
 * {@code R} 的 javadoc 里已经写明 SSE 不得使用它。
 */
@Schema(name = "ChatEvent", description = "SSE 流式对话事件，通过 SSE event 字段区分类型",
        oneOf = {ChatEvent.Delta.class, ChatEvent.Citation.class, ChatEvent.Done.class, ChatEvent.Error.class})
public sealed interface ChatEvent {

    /**
     * SSE event 名称。
     */
    String type();

    /**
     * 增量文本。
     *
     * @param text 本次推送的增量文本
     */
    @Schema(name = "ChatDeltaEvent", description = "delta 事件的数据，包含本次增量文本")
    record Delta(
            @Schema(description = "增量文本") String text) implements ChatEvent {
        @Override
        public String type() {
            return "delta";
        }
    }

    /**
     * 本轮预算内的参考来源，不包含正文。
     *
     * <p>完整三元组可用于受当前权限保护的原版本查询，但不保证片段仍存在。
     * 引用不证明模型实际采用该材料或答案逐句有据；来源范围也不是原件精确坐标。
     *
     * @param docId            文档 ID
     * @param docTitle         文档标题
     * @param pageNo           首个来源块页码；解析器未提供时为 {@code null}
     * @param chunkId          分块 ID
     * @param versionId        命中分块所属版本 ID；旧上游未提供时为 {@code null}
     * @param versionNo        所属版本的展示编号，不用于定位
     * @param chunkIdx         分块序号，不用于回退定位
     * @param headingPath      来源标题路径；未知时为 {@code null}
     * @param structureType    分块结构类型；未知时为 {@code null}
     * @param sourceLineStart  解析器报告的来源起始行
     * @param sourceLineEnd    解析器报告的来源结束行
     * @param sourceCharStart  解析器报告的来源起始字符，不是原件精确坐标
     * @param sourceCharEnd    解析器报告的来源结束字符，不是原件精确坐标
     * @param sourceLocator    解析器提供的来源定位标识
     * @param provenanceStatus 三元组身份完整性，不表示片段当前可用性
     * @param contextTruncated 本轮上下文预算是否裁剪过该片段正文
     */
    @Schema(name = "ChatCitationEvent", description = "citation 事件的本轮参考来源，不证明模型实际采用或逐句验证")
    record Citation(
            @Schema(description = "文档 ID", example = "1001") long docId,
            @Schema(description = "文档标题") String docTitle,
            @Schema(description = "首个来源块页码，不表示整块页范围", nullable = true, example = "3") Integer pageNo,
            @Schema(description = "分块 ID", example = "4001") long chunkId,
            @Schema(description = "分块所属版本 ID；缺失时不可进行版本级解析", nullable = true,
                    example = "2001") Long versionId,
            @Schema(description = "所属版本的展示编号，不用于定位", nullable = true, example = "1") Integer versionNo,
            @Schema(description = "分块序号，不用于回退定位", nullable = true) Integer chunkIdx,
            @Schema(description = "来源标题路径", nullable = true) String headingPath,
            @Schema(description = "分块结构类型", nullable = true) String structureType,
            @Schema(description = "解析器报告的来源起始行，仅作核对提示", nullable = true) Integer sourceLineStart,
            @Schema(description = "解析器报告的来源结束行，仅作核对提示", nullable = true) Integer sourceLineEnd,
            @Schema(description = "解析器报告的来源起始字符，不承诺原件精确坐标", nullable = true) Integer sourceCharStart,
            @Schema(description = "解析器报告的来源结束字符，不承诺原件精确坐标", nullable = true) Integer sourceCharEnd,
            @Schema(description = "来源定位标识，不作为 URL 执行", nullable = true) String sourceLocator,
            @Schema(description = "引用身份完整性，不保证分块当前仍可读取") ProvenanceStatus provenanceStatus,
            @Schema(description = "本轮上下文预算是否裁剪过正文；来源范围仍属于完整分块") boolean contextTruncated)
            implements ChatEvent {

        /**
         * 兼容旧调用方，缺失的版本保持未知，不推断当前版本。
         */
        public Citation(long docId, String docTitle, Integer pageNo, long chunkId) {
            this(docId, docTitle, pageNo, chunkId, null, null, null, null, null, null,
                    null, null, null, null, ProvenanceStatus.UNVERSIONED, false);
        }

        /**
         * 从本轮预算内的参考生成事件，正文不进入公开 DTO。
         */
        public static Citation from(RetrievalClient.Citation source) {
            ProvenanceStatus status = source.docId() > 0 && source.chunkId() > 0
                    && source.versionId() != null && source.versionId() > 0
                    ? ProvenanceStatus.VERSIONED : ProvenanceStatus.UNVERSIONED;
            return new Citation(source.docId(), source.docTitle(), source.pageNo(), source.chunkId(),
                    source.versionId(), source.versionNo(), source.chunkIdx(), source.headingPath(),
                    source.structureType(), source.sourceLineStart(), source.sourceLineEnd(),
                    source.sourceCharStart(), source.sourceCharEnd(), source.sourceLocator(),
                    status, source.contextTruncated());
        }

        @Override
        public String type() {
            return "citation";
        }
    }

    /**
     * 引用是否具备文档、版本和分块的完整身份，不表示片段可用性。
     */
    @Schema(name = "CitationProvenanceStatus", description = "引用身份完整性，独立于原分块当前是否可用")
    enum ProvenanceStatus {
        VERSIONED,
        UNVERSIONED
    }

    /**
     * 正常结束。
     *
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @param elapsedMs        耗时，单位毫秒
     */
    @Schema(name = "ChatDoneEvent", description = "done 事件的数据，包含正常结束时的用量与耗时")
    record Done(
            @Schema(description = "输入 token 数") int promptTokens,
            @Schema(description = "输出 token 数") int completionTokens,
            @Schema(description = "耗时，单位毫秒") long elapsedMs) implements ChatEvent {
        @Override
        public String type() {
            return "done";
        }
    }

    /**
     * 出错结束。
     *
     * @param message 给用户看的说明。<b>不得包含上游返回的原始错误体</b>——
     *                云厂商的错误信息里可能带上我们发过去的 prompt 片段，
     *                而 prompt 里装的是文档正文。
     */
    @Schema(name = "ChatErrorEvent", description = "error 事件的数据，包含面向用户的错误说明")
    record Error(
            @Schema(description = "给用户看的错误说明") String message) implements ChatEvent {
        @Override
        public String type() {
            return "error";
        }
    }
}

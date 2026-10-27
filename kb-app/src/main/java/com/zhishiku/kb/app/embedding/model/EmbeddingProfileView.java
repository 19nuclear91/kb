package com.zhishiku.kb.app.embedding.model;

import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理接口的嵌入配置视图；保持核心嵌入契约不依赖 API 文档框架。
 *
 * @param profileId                不可变嵌入配置标识
 * @param provider                 模型服务提供方类型
 * @param modelSource              模型来源标识
 * @param model                    模型名称
 * @param modelRevision            模型版本标识
 * @param dimension                向量维度
 * @param distance                 向量距离度量
 * @param queryTransformVersion    查询文本变换版本
 * @param documentTransformVersion 文档文本变换版本
 * @param queryTaskType            查询向量任务类型
 * @param documentTaskType         文档向量任务类型
 * @param adapterVersion           模型适配器版本
 */
@Schema(description = "嵌入模型的不可变语义配置，不包含端点与密钥")
public record EmbeddingProfileView(
        @Schema(description = "不可变嵌入配置标识") String profileId,
        @Schema(description = "模型服务提供方类型") String provider,
        @Schema(description = "模型来源标识") String modelSource,
        @Schema(description = "模型名称") String model,
        @Schema(description = "模型版本标识") String modelRevision,
        @Schema(description = "向量维度") int dimension,
        @Schema(description = "向量距离度量") String distance,
        @Schema(description = "查询文本变换版本") String queryTransformVersion,
        @Schema(description = "文档文本变换版本") String documentTransformVersion,
        @Schema(description = "查询向量任务类型") String queryTaskType,
        @Schema(description = "文档向量任务类型") String documentTaskType,
        @Schema(description = "模型适配器版本") String adapterVersion) {
    /**
     * 从核心配置复制同名字段，保持对外 JSON 结构不变。
     */
    public static EmbeddingProfileView from(EmbeddingProfile profile) {
        return new EmbeddingProfileView(
                profile.profileId(),
                profile.provider(),
                profile.modelSource(),
                profile.model(),
                profile.modelRevision(),
                profile.dimension(),
                profile.distance(),
                profile.queryTransformVersion(),
                profile.documentTransformVersion(),
                profile.queryTaskType(),
                profile.documentTaskType(),
                profile.adapterVersion());
    }
}

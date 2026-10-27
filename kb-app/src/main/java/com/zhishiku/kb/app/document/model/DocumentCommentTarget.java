package com.zhishiku.kb.app.document.model;

import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.PublicationProtocol;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import com.zhishiku.kb.common.review.VersionAccessRules;
import com.zhishiku.kb.common.review.VersionPublication;

import java.time.Instant;

/**
 * 固定版本评论目标的当次资格事实；不含标题、文件名、正文或存储键。
 *
 * @param documentId 文档标识
 * @param versionId 固定版本标识
 * @param live 文档未删除未归档且空间未归档
 * @param cleared 当前主体密级不低于文档密级
 * @param viewGranted 当前主体经空间或文档例外持有 view
 * @param commentGranted 当前主体经空间或文档例外持有 comment
 * @param policy 版本创建时固定的发布策略
 * @param protocol 版本协议来源
 * @param uploaderUserId 版本上传者；旧兼容版本可空
 * @param evidence 版本发布证据
 * @param publishedAt 实际发布时间；观察到的旧发布为空
 */
public record DocumentCommentTarget(long documentId, long versionId, boolean live, boolean cleared,
                                    boolean viewGranted, boolean commentGranted, PublicationPolicy policy,
                                    PublicationProtocol protocol, Long uploaderUserId, PublicationEvidence evidence,
                                    Instant publishedAt) {
    public VersionPublication publication() {
        return new VersionPublication(policy, protocol, uploaderUserId, evidence, publishedAt);
    }

    /** 读取要求当前 view、密级、未删除未归档及发布证据；旧兼容标记、索引状态或审核参与身份都不能替代。 */
    public boolean readable() {
        return VersionAccessRules.allows(publication(), VersionAccessPurpose.COMMENT_TARGET,
                live && cleared && viewGranted, false);
    }

    /** 发表另需 comment；comment 不暗含 view，view、edit、review、manage 也不替代 comment。 */
    public boolean commentable() {
        return readable() && commentGranted;
    }
}

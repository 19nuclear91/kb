package com.zhishiku.kb.common.review;

/**
 * 持有控制及资源锁时读取的发布前置事实；生成 epoch 可以早于当前 epoch，但身份必须相同。
 * @param active 当前控制是否 ACTIVE
 * @param activeProfileId 当前 profile
 * @param activeFingerprint 当前语义指纹
 * @param controlEpoch 当前控制代次
 * @param indexedProfileId 实际索引生成 profile
 * @param indexedFingerprint 实际索引生成指纹
 * @param indexedEpoch 实际索引生成代次
 * @param chunksIntact 全部分块数量、序号、归属、空间、密级及 profile 完整
 * @param indexStatus 当前索引轴状态
 * @param live 文档及实际空间当前可发布
 * @param versionNo 目标版本序号
 * @param currentVersionNo 当前发布版本序号，无当前版本时为空
 */
public record PublicationCheck(boolean active, String activeProfileId, String activeFingerprint, long controlEpoch,
                                String indexedProfileId, String indexedFingerprint, Long indexedEpoch,
                                boolean chunksIntact, String indexStatus, boolean live, int versionNo, Integer currentVersionNo) {
}

package com.zhishiku.kb.app.document.model;

import java.time.Instant;

/**
 * 收藏列表的内部查询结果；收藏关系 ID 仅用于续页。
 *
 * @param favoriteId 收藏创建位置
 * @param documentId 文档标识
 * @param title 当前文档标题
 * @param spaceId 当前所属空间
 * @param securityLevel 当前密级
 * @param currentVersionId 当前发布版本标识，可空
 * @param currentVersionNo 当前发布版本号，可空
 * @param favoritedAt 收藏创建时间
 */
public record DocumentFavoriteRow(long favoriteId, long documentId, String title, long spaceId,
                                  short securityLevel, Long currentVersionId, Integer currentVersionNo,
                                  Instant favoritedAt) {
    public DocumentFavoriteViews.Item view() {
        return new DocumentFavoriteViews.Item(documentId, title, spaceId, securityLevel,
                currentVersionId, currentVersionNo, favoritedAt);
    }
}

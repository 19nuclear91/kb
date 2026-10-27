import { ApiError, positiveId } from '../api';
import type { Session } from '../session';

export type NotificationType = 'REVIEW_SUBMITTED' | 'REVIEW_APPROVED' | 'REVIEW_REJECTED' | 'COMMENT_REPLIED';
export type NotificationTarget =
  | { readonly kind: 'DOCUMENT_VERSION_REVIEW'; readonly documentId: string; readonly versionId: string; readonly reviewId: string; readonly commentId: null }
  | { readonly kind: 'DOCUMENT_VERSION_COMMENT'; readonly documentId: string; readonly versionId: string; readonly reviewId: null; readonly commentId: string };
export interface NotificationItem {
  notificationId: string; type: NotificationType; createdAt: string; readAt: string | null; target: NotificationTarget;
}
export interface NotificationPage { items: NotificationItem[]; hasMore: boolean; nextCursor: string | null; asOf: string }
export interface UnreadCount { count: string; asOf: string }
export interface ReadReceipt { notificationId: string; readAt: string }

/** 页面只显示本地固定提示，不渲染服务端或源业务文本。 */
export const notificationLabels: Record<NotificationType, string> = {
  REVIEW_SUBMITTED: '有版本等待你审核', REVIEW_APPROVED: '你送审的版本已通过',
  REVIEW_REJECTED: '你送审的版本被驳回', COMMENT_REPLIED: '你的评论有新回复',
};
const types = new Set<string>(Object.keys(notificationLabels));
const base = '/api/notifications';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CURSOR = /^[A-Za-z0-9_-]{1,2731}$/;

export function notificationId(value: unknown): string {
  if (typeof value !== 'string' || !UUID.test(value)) throw new ApiError(400);
  return value.toLowerCase();
}

function exactKeys(value: unknown, keys: readonly string[]): value is Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  return actual.length === keys.length && [...keys].sort().every((key, index) => key === actual[index]);
}
const timestamp = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value));

/** 只接受白名单结构化目标；外链、令牌、returnUrl 或额外字段一律视为无效响应。 */
export function notificationTarget(value: unknown): NotificationTarget {
  if (!exactKeys(value, ['kind', 'documentId', 'versionId', 'reviewId', 'commentId'])) throw new ApiError(503);
  try {
    const documentId = positiveId(value.documentId), versionId = positiveId(value.versionId);
    if (value.kind === 'DOCUMENT_VERSION_REVIEW' && value.commentId === null)
      return Object.freeze({ kind: value.kind, documentId, versionId, reviewId: notificationId(value.reviewId), commentId: null });
    if (value.kind === 'DOCUMENT_VERSION_COMMENT' && value.reviewId === null)
      return Object.freeze({ kind: value.kind, documentId, versionId, reviewId: null, commentId: notificationId(value.commentId) });
  } catch { /* 统一转换为响应无效。 */ }
  throw new ApiError(503);
}

function item(value: unknown): NotificationItem {
  if (!exactKeys(value, ['notificationId', 'type', 'prompt', 'createdAt', 'readAt', 'target'])
    || typeof value.type !== 'string' || !types.has(value.type) || typeof value.prompt !== 'string'
    || !timestamp(value.createdAt) || (value.readAt !== null && !timestamp(value.readAt))) throw new ApiError(503);
  let id: string;
  try { id = notificationId(value.notificationId); } catch { throw new ApiError(503); }
  const target = notificationTarget(value.target), type = value.type as NotificationType;
  if ((type === 'COMMENT_REPLIED') !== (target.kind === 'DOCUMENT_VERSION_COMMENT')) throw new ApiError(503);
  return { notificationId: id, type, createdAt: value.createdAt as string, readAt: value.readAt as string | null, target };
}

export const notificationPaths = {
  list: (cursor?: string | null) => {
    if (cursor != null && !CURSOR.test(cursor)) throw new ApiError(400);
    return `${base}?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
  },
  unread: () => `${base}/unread-count`,
  read: (id: string) => `${base}/${notificationId(id)}/read`,
};

export async function notificationPage(session: Session, cursor: string | null, signal: AbortSignal): Promise<NotificationPage> {
  const result = await session.json<unknown>(notificationPaths.list(cursor), { signal });
  if (!exactKeys(result, ['items', 'hasMore', 'nextCursor', 'asOf']) || !Array.isArray(result.items) || result.items.length > 20
    || typeof result.hasMore !== 'boolean' || !timestamp(result.asOf)
    || (result.hasMore ? typeof result.nextCursor !== 'string' || !CURSOR.test(result.nextCursor) || !result.items.length
      : result.nextCursor !== null)) throw new ApiError(503);
  const items = result.items.map(item);
  if (new Set(items.map(value => value.notificationId)).size !== items.length) throw new ApiError(503);
  return { items, hasMore: result.hasMore, nextCursor: result.nextCursor as string | null, asOf: result.asOf as string };
}

export async function unreadCount(session: Session, signal: AbortSignal): Promise<UnreadCount> {
  const result = await session.json<unknown>(notificationPaths.unread(), { signal });
  if (!exactKeys(result, ['count', 'asOf']) || typeof result.count !== 'string' || !/^(?:0|[1-9]\d{0,18})$/.test(result.count)
    || BigInt(result.count) > 9223372036854775807n || !timestamp(result.asOf)) throw new ApiError(503);
  return { count: result.count, asOf: result.asOf as string };
}

/** 幂等已读；回执与请求不一致视为结果未确认（状态 0），只允许显式重试同一通知。 */
export async function markRead(session: Session, id: string, signal: AbortSignal): Promise<ReadReceipt> {
  const path = notificationPaths.read(id), expected = notificationId(id);
  const result = await session.json<unknown>(path, { method: 'PUT', signal });
  if (!exactKeys(result, ['notificationId', 'readAt']) || typeof result.notificationId !== 'string'
    || result.notificationId.toLowerCase() !== expected || !timestamp(result.readAt)) throw new ApiError(0);
  return { notificationId: expected, readAt: result.readAt as string };
}

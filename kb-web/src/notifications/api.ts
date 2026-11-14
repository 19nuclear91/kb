import { ApiError, positiveId } from '../api';
import type { Session } from '../session';

export type NotificationType = 'REVIEW_SUBMITTED' | 'REVIEW_APPROVED' | 'REVIEW_REJECTED' | 'COMMENT_REPLIED'
  | 'GAP_CREATED' | 'GAP_RETURNED' | 'GAP_ASSIGNED' | 'GAP_SOLUTION_SUBMITTED' | 'GAP_SOLUTION_REJECTED' | 'GAP_RESOLVED' | 'GAP_WITHDRAWN' | 'GAP_CLOSED';
export type NotificationTarget =
  | { readonly kind: 'DOCUMENT_VERSION_REVIEW'; readonly documentId: string; readonly versionId: string; readonly reviewId: string; readonly commentId: null }
  | { readonly kind: 'DOCUMENT_VERSION_COMMENT'; readonly documentId: string; readonly versionId: string; readonly reviewId: null; readonly commentId: string }
  | { readonly kind: 'KNOWLEDGE_GAP'; readonly gapId: string };
export const NOTIFICATION_CONTRACT = 'KNOWLEDGE_GAPS_V1';
export const notificationHeaders = Object.freeze({ 'X-Kb-Notification-Contract': NOTIFICATION_CONTRACT });
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
  GAP_CREATED: '有知识问题等待受理', GAP_RETURNED: '有知识问题退回受理', GAP_ASSIGNED: '有知识问题分派给你',
  GAP_SOLUTION_SUBMITTED: '你的知识问题有方案待确认', GAP_SOLUTION_REJECTED: '知识问题方案仍未解决',
  GAP_RESOLVED: '知识问题已由提问者确认解决', GAP_WITHDRAWN: '知识问题已被提问者撤回', GAP_CLOSED: '知识问题已关闭',
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
function exactId(value: unknown): string {
  if (!['string', 'number', 'bigint'].includes(typeof value) || (typeof value === 'number' && !Number.isSafeInteger(value))) throw new ApiError(503);
  return positiveId(value);
}

/** 只接受白名单结构化目标；外链、令牌、returnUrl 或额外字段一律视为无效响应。 */
export function notificationTarget(value: unknown): NotificationTarget {
  if (exactKeys(value, ['kind', 'gapId']) && value.kind === 'KNOWLEDGE_GAP') {
    try { return Object.freeze({ kind: value.kind, gapId: exactId(value.gapId) }); } catch { throw new ApiError(503); }
  }
  if (!exactKeys(value, ['kind', 'documentId', 'versionId', 'reviewId', 'commentId'])) throw new ApiError(503);
  try {
    const documentId = exactId(value.documentId), versionId = exactId(value.versionId);
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
  const kind = type.startsWith('GAP_') ? 'KNOWLEDGE_GAP' : type === 'COMMENT_REPLIED' ? 'DOCUMENT_VERSION_COMMENT' : 'DOCUMENT_VERSION_REVIEW';
  if (target.kind !== kind) throw new ApiError(503);
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
  const result = await session.json<unknown>(notificationPaths.list(cursor), { signal, headers: notificationHeaders });
  if (!exactKeys(result, ['items', 'hasMore', 'nextCursor', 'asOf']) || !Array.isArray(result.items) || result.items.length > 20
    || typeof result.hasMore !== 'boolean' || !timestamp(result.asOf)
    || (result.hasMore ? typeof result.nextCursor !== 'string' || !CURSOR.test(result.nextCursor) || !result.items.length
      : result.nextCursor !== null)) throw new ApiError(503);
  const items = result.items.map(item);
  if (new Set(items.map(value => value.notificationId)).size !== items.length) throw new ApiError(503);
  return { items, hasMore: result.hasMore, nextCursor: result.nextCursor as string | null, asOf: result.asOf as string };
}

export async function unreadCount(session: Session, signal: AbortSignal): Promise<UnreadCount> {
  const result = await session.json<unknown>(notificationPaths.unread(), { signal, headers: notificationHeaders });
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

import { ApiError, decodeJson, encodeJson, positiveId, readJson } from '../api';
import type { Session } from '../session';
import type { WriteIntent } from '../admin/api';

export const COMMENT_MAX = 4000;
const LIMIT = 20;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CURSOR = /^[A-Za-z0-9_-]{1,2731}$/;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;

export interface CommentAuthor { userId: string; label: string }
/** 固定版本上的一条评论；墓碑只保留定位与结构，author/body 为空。 */
export interface CommentItem {
  commentId: string; parentId: string | null; documentId: string; versionId: string; createdAt: string; deleted: boolean;
  author: CommentAuthor | null; body: string | null; isOwn: boolean; canDelete: boolean;
}
export interface CommentPage { items: CommentItem[]; hasMore: boolean; nextCursor: string | null; canCreate: boolean }
export interface CommentDetail { item: CommentItem; canReply: boolean }
export interface CommentReceipt {
  commentId: string; parentId: string | null; documentId: string; versionId: string; createdAt: string; deleted: boolean; replayed: boolean;
}

export function commentId(value: unknown): string {
  if (typeof value !== 'string' || !UUID.test(value)) throw new ApiError(400);
  return value.toLowerCase();
}

/** 与服务端 Character.isWhitespace 一致；不剥离不换行空格（U+00A0、U+2007、U+202F）。 */
function whitespace(code: number): boolean {
  if (code === 0x00a0 || code === 0x2007 || code === 0x202f) return false;
  if ((code >= 0x09 && code <= 0x0d) || (code >= 0x1c && code <= 0x20)) return true;
  return code === 0x1680 || (code >= 0x2000 && code <= 0x200a) || code === 0x2028 || code === 0x2029 || code === 0x205f || code === 0x3000;
}
function strip(value: string): string {
  const points = [...value];
  let start = 0, end = points.length;
  while (start < end && whitespace(points[start].codePointAt(0)!)) start++;
  while (end > start && whitespace(points[end - 1].codePointAt(0)!)) end--;
  return points.slice(start, end).join('');
}
/** 去首尾空白后的 code point 数；补充平面字符计 1。 */
export function commentLength(value: string): number { return [...strip(value)].length; }
/**
 * 与服务端同一规则：只去首尾空白，不做 Unicode 兼容折叠或换行改写，不静默截断；
 * 1–4000 个 code point，拒绝 NUL 与孤立代理。
 */
export function commentBody(value: string): string {
  const text = strip(value), points = [...text];
  if (!points.length || points.length > COMMENT_MAX) throw new ApiError(400);
  for (const point of points) {
    const code = point.codePointAt(0)!;
    if (code === 0 || (code >= 0xd800 && code <= 0xdfff)) throw new ApiError(400);
  }
  return text;
}

const versionPath = (d: string, v: string) => `/api/documents/${positiveId(d)}/versions/${positiveId(v)}/comments`;
const query = (cursor?: string | null) => {
  if (cursor != null && !CURSOR.test(cursor)) throw new ApiError(400);
  return `?limit=${LIMIT}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
};
export const commentPaths = {
  roots: (d: string, v: string, cursor?: string | null) => `${versionPath(d, v)}${query(cursor)}`,
  replies: (d: string, v: string, root: string, cursor?: string | null) => `${versionPath(d, v)}/${commentId(root)}/replies${query(cursor)}`,
  detail: (d: string, v: string, comment: string) => `${versionPath(d, v)}/${commentId(comment)}`,
  create: (d: string, v: string) => versionPath(d, v),
};

function exactKeys(value: unknown, keys: readonly string[]): value is Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const actual = Object.keys(value).sort();
  return actual.length === keys.length && [...keys].sort().every((key, index) => key === actual[index]);
}
const instant = (value: unknown) => typeof value === 'string' && INSTANT.test(value) && Number.isFinite(Date.parse(value));

/** 只接受本版本、精确 ID 的最小投影；删除权限只能是本人，墓碑不得带作者或正文。 */
function item(value: unknown, documentId: string, versionId: string): CommentItem {
  if (!exactKeys(value, ['commentId', 'parentId', 'documentId', 'versionId', 'createdAt', 'deleted', 'author', 'body', 'isOwn', 'canDelete'])
    || typeof value.deleted !== 'boolean' || typeof value.isOwn !== 'boolean' || typeof value.canDelete !== 'boolean'
    || !instant(value.createdAt) || value.canDelete !== value.isOwn) throw new ApiError(503);
  const id = commentId(value.commentId), parent = value.parentId === null ? null : commentId(value.parentId);
  if (positiveId(value.documentId) !== documentId || positiveId(value.versionId) !== versionId || parent === id) throw new ApiError(503);
  let author: CommentAuthor | null = null, body: string | null = null;
  if (value.deleted) {
    if (value.author !== null || value.body !== null || value.isOwn) throw new ApiError(503);
  } else {
    if (!exactKeys(value.author, ['userId', 'label']) || typeof value.author.label !== 'string' || !value.author.label
      || typeof value.body !== 'string' || !value.body || [...value.body].length > COMMENT_MAX) throw new ApiError(503);
    author = { userId: positiveId(value.author.userId), label: value.author.label };
    body = value.body;
  }
  return { commentId: id, parentId: parent, documentId, versionId, createdAt: value.createdAt as string, deleted: value.deleted,
    author, body, isOwn: value.isOwn, canDelete: value.canDelete };
}

function page(value: unknown, documentId: string, versionId: string, root: string | null): CommentPage {
  if (!exactKeys(value, ['items', 'hasMore', 'nextCursor', 'canCreate']) || !Array.isArray(value.items) || value.items.length > LIMIT
    || typeof value.hasMore !== 'boolean' || typeof value.canCreate !== 'boolean'
    || (value.hasMore ? typeof value.nextCursor !== 'string' || !CURSOR.test(value.nextCursor) || !value.items.length
      : value.nextCursor !== null)) throw new ApiError(503);
  const items = value.items.map(entry => item(entry, documentId, versionId));
  if (items.some(entry => entry.parentId !== root) || new Set(items.map(entry => entry.commentId)).size !== items.length) throw new ApiError(503);
  return { items, hasMore: value.hasMore, nextCursor: value.nextCursor as string | null, canCreate: value.canCreate };
}

/** HTTP 与身份故障原样上抛；响应形状不符一律 503，调用方不能把失败解释为空讨论。 */
async function checked<T>(read: Promise<unknown>, validate: (value: unknown) => T): Promise<T> {
  const value = await read;
  try { return validate(value); } catch { throw new ApiError(503); }
}
export async function rootPage(session: Session, d: string, v: string, cursor: string | null, signal: AbortSignal): Promise<CommentPage> {
  const documentId = positiveId(d), versionId = positiveId(v);
  return checked(session.json<unknown>(commentPaths.roots(documentId, versionId, cursor), { signal }),
    value => page(value, documentId, versionId, null));
}
export async function replyPage(session: Session, d: string, v: string, root: string, cursor: string | null, signal: AbortSignal): Promise<CommentPage> {
  const documentId = positiveId(d), versionId = positiveId(v), rootId = commentId(root);
  return checked(session.json<unknown>(commentPaths.replies(documentId, versionId, rootId, cursor), { signal }),
    value => page(value, documentId, versionId, rootId));
}
export async function commentDetail(session: Session, d: string, v: string, comment: string, signal: AbortSignal): Promise<CommentDetail> {
  const documentId = positiveId(d), versionId = positiveId(v), id = commentId(comment);
  return checked(session.json<unknown>(commentPaths.detail(documentId, versionId, id), { signal }), value => {
    if (!exactKeys(value, ['item', 'canReply']) || typeof value.canReply !== 'boolean') throw new ApiError(503);
    const target = item(value.item, documentId, versionId);
    if (target.commentId !== id) throw new ApiError(503);
    return { item: target, canReply: value.canReply };
  });
}

/** 写意图携带用于校验回执的目标；body 是规范化后的精确提交值，改变即新绑定。 */
export interface CommentWriteIntent extends WriteIntent {
  comment: { action: 'create' | 'delete'; documentId: string; versionId: string; parentId: string | null; commentId: string | null };
}
export function createIntent(d: string, v: string, parent: string | null, body: string): CommentWriteIntent {
  const documentId = positiveId(d), versionId = positiveId(v), parentId = parent === null ? null : commentId(parent);
  const text = commentBody(body);
  return { method: 'POST', path: commentPaths.create(documentId, versionId), body: parentId === null ? { body: text } : { parentId, body: text },
    comment: { action: 'create', documentId, versionId, parentId, commentId: null } };
}
export function deleteIntent(d: string, v: string, comment: string): CommentWriteIntent {
  const documentId = positiveId(d), versionId = positiveId(v), id = commentId(comment);
  return { method: 'DELETE', path: commentPaths.detail(documentId, versionId, id),
    comment: { action: 'delete', documentId, versionId, parentId: null, commentId: id } };
}

const failures = {
  INVALID_REQUEST: [400, '评论内容或请求不符合要求，请检查后重新提交。'],
  FORBIDDEN: [403, '当前不可发表评论，正在重新核对资格。'],
  NOT_FOUND: [404, '评论或版本当前不可访问。'],
  STATE_CONFLICT: [409, '根评论已删除或不能被回复，请重新读取讨论。'],
} as const;
export type CommentFailure = keyof typeof failures;
export class CommentError extends ApiError {
  constructor(status: number, public readonly code: CommentFailure) { super(status); this.message = failures[code][1]; }
}
/** 只读取有界 JSON 失败体中的固定错误码；状态与错误码不一致时退回通用提示。 */
async function failure(response: Response): Promise<ApiError> {
  const status = response.status;
  if (!response.body || !response.headers.get('Content-Type')?.includes('application/json')) { await response.body?.cancel().catch(() => {}); return new ApiError(status); }
  const reader = response.body.getReader(), chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const part = await reader.read();
    if (part.done) break;
    size += part.value.byteLength;
    if (size > 8192) { await reader.cancel().catch(() => {}); return new ApiError(status); }
    chunks.push(part.value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try {
    const code = decodeJson<{ data?: { errorCode?: string } }>(new TextDecoder().decode(bytes)).data?.errorCode;
    if (code && Object.hasOwn(failures, code) && failures[code as CommentFailure][0] === status) return new CommentError(status, code as CommentFailure);
  } catch { /* 只显示固定安全提示。 */ }
  return new ApiError(status);
}
function receipt(value: unknown, expected: CommentWriteIntent['comment'], replayed: boolean): CommentReceipt {
  if (!exactKeys(value, ['commentId', 'parentId', 'documentId', 'versionId', 'createdAt', 'deleted', 'replayed'])
    || value.replayed !== replayed || typeof value.deleted !== 'boolean' || (!replayed && value.deleted) || !instant(value.createdAt)) throw new ApiError(0);
  const parent = value.parentId === null ? null : commentId(value.parentId);
  if (parent !== expected.parentId || positiveId(value.documentId) !== expected.documentId || positiveId(value.versionId) !== expected.versionId) throw new ApiError(0);
  return { commentId: commentId(value.commentId), parentId: parent, documentId: expected.documentId, versionId: expected.versionId,
    createdAt: value.createdAt as string, deleted: value.deleted, replayed };
}

/**
 * 评论写执行器：请求标识只走 X-Request-Id；新建 201、匹配重放 200，删除 204。
 * 身份切换或中止按 AbortError 处理；成功但回执不可读或不匹配视为结果不确定（状态 0）。
 */
export async function commentWrite<T>(session: Session, intent: WriteIntent, requestId: string, signal?: AbortSignal): Promise<T> {
  const expected = (intent as CommentWriteIntent).comment;
  if (!expected || !/^[\x21-\x7e]{1,128}$/.test(requestId)) throw new ApiError(400);
  const epoch = session.getSnapshot().epoch;
  const headers: Record<string, string> = { 'X-Request-Id': requestId };
  if (intent.body) headers['Content-Type'] = 'application/json';
  const response = await session.send(intent.path, { method: intent.method, headers, body: intent.body ? encodeJson(intent.body) : undefined, signal });
  const stale = () => signal?.aborted === true || epoch !== session.getSnapshot().epoch;
  if (stale()) { await response.body?.cancel().catch(() => {}); throw new DOMException('请求已停止', 'AbortError'); }
  if (!response.ok) { const error = await failure(response); if (stale()) throw new DOMException('请求已停止', 'AbortError'); throw error; }
  try {
    if (expected.action === 'delete') {
      if (response.status !== 204) throw new ApiError(0);
      await response.body?.cancel().catch(() => {});
      if (stale()) throw new DOMException('请求已停止', 'AbortError');
      return undefined as T;
    }
    if (response.status !== 201 && response.status !== 200) throw new ApiError(0);
    const value = await readJson<unknown>(response);
    if (stale()) throw new DOMException('请求已停止', 'AbortError');
    return receipt(value, expected, response.status === 200) as T;
  } catch (error) {
    if (stale() || (error instanceof Error && error.name === 'AbortError')) throw new DOMException('请求已停止', 'AbortError');
    throw new ApiError(0);
  }
}

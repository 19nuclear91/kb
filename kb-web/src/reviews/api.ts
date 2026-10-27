import { ApiError, decodeJson, encodeJson, positiveId, readJson } from '../api';
import type { Session } from '../session';
import type { WriteIntent } from '../admin/api';

export type Policy = 'AUTO' | 'REVIEW_REQUIRED';
export type Evidence = 'NONE' | 'PUBLISHED' | 'OBSERVED_CURRENT';
export type Exact = string | number | bigint;
export interface ReviewState {
  documentId: string; versionId: string; versionNo: number; stateVersion: Exact; indexStatus: string;
  policy: Policy; publicationEvidence: Evidence; reviewState: string; reviewId?: string; roundNo?: number;
  currentVersionId?: string; waitingReason?: string;
}
export interface ReviewCapabilities {
  canRead: boolean; canDownload: boolean; canSubmit: boolean; canApprove: boolean; canReject: boolean;
  canWithdraw: boolean; canRefresh: boolean; canReadOpinions: boolean;
}
export interface ReviewRound { reviewId: string; roundNo: number; state: string; submittedBy: string; submittedAt: string; decidedAt?: string; reason?: string }
export interface ReviewStatus { state: ReviewState; capabilities: ReviewCapabilities; rounds: ReviewRound[]; hasMore: boolean; nextCursor: string | null }
export interface ReviewItem { state: ReviewState; canRead: boolean; title?: string }
export interface ReviewPage { items: ReviewItem[]; hasMore: boolean; nextCursor: string | null }
export interface PublicationPolicy { spaceId: string; policy: Policy; version: Exact }
export interface ReviewReceipt { documentId: string; versionId: string; reviewId: string | null; roundNo: number | null; stateVersion: Exact;
  indexStatus: string; policy: Policy; reviewState: string | null; publicationEvidence: Evidence; occurredAt: string }

export function exact(value: Exact): bigint {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new ApiError(400);
  const text = String(value);
  if (!/^(?:0|[1-9]\d{0,18})$/.test(text) || BigInt(text) > 9223372036854775807n) throw new ApiError(400);
  return BigInt(text);
}
export function reviewId(value: string): string {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)) throw new ApiError(400);
  return value;
}
const versionPath = (d: string, v: string) => `/api/documents/${positiveId(d)}/versions/${positiveId(v)}`;
export const reviewPaths = {
  list: (kind: 'mine' | 'pending', cursor?: string | null) => {
    if (!['mine', 'pending'].includes(kind)) throw new ApiError(400);
    return `/api/reviews?kind=${kind}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
  },
  status: (d: string, v: string, opinions = false, cursor?: string | null) => `${versionPath(d, v)}/review${opinions ? `?opinions=true${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}` : ''}`,
  policy: (spaceId: string) => `/api/admin/spaces/${positiveId(spaceId)}/publication-policy`,
};
export type ReviewCommand = 'submit' | 'approve' | 'reject' | 'withdraw' | 'refresh';
interface ReviewWriteIntent extends WriteIntent { review: { command: ReviewCommand; state: ReviewState } }
export function reviewIntent(command: ReviewCommand, state: ReviewState, reason = ''): ReviewWriteIntent {
  const base = versionPath(state.documentId, state.versionId), body: Record<string, unknown> = { expectedStateVersion: exact(state.stateVersion) };
  let path: string;
  if (command === 'submit') path = `${base}/reviews`;
  else if (command === 'refresh') path = `${base}/index-refresh`;
  else {
    if (!['approve', 'reject', 'withdraw'].includes(command)) throw new ApiError(400);
    path = `${base}/reviews/${reviewId(state.reviewId ?? '')}/${command}`;
  }
  if (command === 'reject') {
    const normalized = reason.replace(/\r\n?/g, '\n').normalize('NFC').trim();
    if (!normalized || [...normalized].length > 2000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(normalized)) throw new ApiError(400);
    body.reason = normalized;
  }
  // 原轮次只用于校验回执，不随请求正文发送；不确定重试保留同一份快照。
  return { method: 'POST', path, body, review: { command, state: { ...state } } };
}
export function policyIntent(spaceId: string, policy: Policy, version: Exact): WriteIntent {
  if (!['AUTO', 'REVIEW_REQUIRED'].includes(policy)) throw new ApiError(400);
  return { method: 'PATCH', path: reviewPaths.policy(spaceId), body: { policy, expectedVersion: exact(version) } };
}
const failures = {
  STATE_CONFLICT: [409, '状态或轮次已变化，请重新读取后确认。'],
  NO_ELIGIBLE_REVIEWER: [409, '当前没有其他合格审核人，请联系空间管理员配置授权。'],
  EMBEDDING_MAINTENANCE: [409, '索引维护中，请恢复后重新确认。'],
  INDEX_REFRESH_REQUIRED: [409, '候选索引需要刷新；刷新完成后必须重新送审。'],
} as const;
export class ReviewError extends ApiError {
  constructor(status: number, public readonly code?: keyof typeof failures) { super(status); if (code) this.message = failures[code][1]; }
}
async function failure(response: Response): Promise<ApiError> {
  if (!response.body || !response.headers.get('Content-Type')?.includes('application/json')) { await response.body?.cancel(); return new ApiError(response.status); }
  const reader = response.body.getReader(); let size = 0; const chunks: Uint8Array[] = [];
  for (;;) {
    const part = await reader.read(); if (part.done) break;
    size += part.value.byteLength;
    if (size > 8192) { await reader.cancel(); return new ApiError(response.status); }
    chunks.push(part.value);
  }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  try {
    const body = decodeJson<{ data?: { errorCode?: string } }>(new TextDecoder().decode(bytes));
    const code = body.data?.errorCode;
    if (code && Object.hasOwn(failures, code) && failures[code as keyof typeof failures][0] === response.status)
      return new ReviewError(response.status, code as keyof typeof failures);
  } catch { /* 仅显示固定安全提示。 */ }
  return new ApiError(response.status);
}
function validateReceipt(receipt: Record<string, unknown>, intent: WriteIntent) {
  const require = (valid: boolean) => { if (!valid) throw new ApiError(0); };
  if (intent.path.endsWith('/publication-policy')) {
    const spaceId = positiveId(receipt.spaceId);
    require(intent.method === 'PATCH' && intent.path === reviewPaths.policy(spaceId));
    require(['AUTO', 'REVIEW_REQUIRED'].includes(String(receipt.policy)) && receipt.policy === intent.body?.policy);
    require(exact(receipt.version as Exact) === exact(intent.body?.expectedVersion as Exact) + 1n);
    return;
  }
  const expected = (intent as ReviewWriteIntent).review;
  require(!!expected && intent.method === 'POST');
  const { command, state } = expected;
  require(positiveId(receipt.documentId) === state.documentId && positiveId(receipt.versionId) === state.versionId);
  require(receipt.policy === state.policy && ['AUTO', 'REVIEW_REQUIRED'].includes(String(receipt.policy)));
  require(['pending', 'parsing', 'indexed', 'failed'].includes(String(receipt.indexStatus)));
  require(['NONE', 'PUBLISHED', 'OBSERVED_CURRENT'].includes(String(receipt.publicationEvidence)));
  require(receipt.reviewState === null || ['IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED'].includes(String(receipt.reviewState)));
  require(typeof receipt.occurredAt === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(receipt.occurredAt) && Number.isFinite(Date.parse(receipt.occurredAt)));
  if (receipt.reviewId === null) require(receipt.roundNo === null);
  else {
    require(typeof receipt.reviewId === 'string'); reviewId(receipt.reviewId as string);
    require(Number.isInteger(receipt.roundNo) && (receipt.roundNo as number) > 0 && (receipt.roundNo as number) <= 2147483647);
  }
  const cancelling = command === 'refresh' && state.reviewState === 'IN_REVIEW';
  require(exact(receipt.stateVersion as Exact) === exact(intent.body?.expectedStateVersion as Exact) + (cancelling ? 2n : 1n));
  if (command === 'submit') {
    require(receipt.reviewId !== null && receipt.reviewId !== state.reviewId && receipt.roundNo === (state.roundNo ?? 0) + 1);
    require(receipt.reviewState === 'IN_REVIEW' && receipt.policy === 'REVIEW_REQUIRED' && receipt.indexStatus === 'indexed' && receipt.publicationEvidence === 'NONE');
  } else if (command === 'refresh') {
    require(receipt.reviewId === (cancelling ? state.reviewId : null) && receipt.roundNo === (cancelling ? state.roundNo : null));
    require(receipt.reviewState === (cancelling ? 'CANCELLED' : null) && receipt.indexStatus === 'pending' && receipt.publicationEvidence === 'NONE');
  } else {
    require(typeof state.reviewId === 'string' && receipt.reviewId === state.reviewId && receipt.roundNo === state.roundNo);
    require(receipt.reviewState === ({ approve: 'APPROVED', reject: 'REJECTED', withdraw: 'WITHDRAWN' } as const)[command]);
    require(receipt.indexStatus === state.indexStatus && receipt.publicationEvidence === (command === 'approve' ? 'PUBLISHED' : 'NONE'));
  }
}
export async function reviewWrite<T>(session: Session, intent: WriteIntent, requestId: string, signal?: AbortSignal): Promise<T> {
  if (!/^[\x21-\x7e]{1,128}$/.test(requestId)) throw new ApiError(400);
  const epoch = session.getSnapshot().epoch;
  const response = await session.send(intent.path, { method: intent.method,
    headers: { 'X-Request-Id': requestId, 'Content-Type': 'application/json' }, body: encodeJson(intent.body), signal });
  const stale = () => signal?.aborted || epoch !== session.getSnapshot().epoch;
  if (stale()) { await response.body?.cancel(); throw new DOMException('请求已停止', 'AbortError'); }
  if (!response.ok) { const error = await failure(response); if (stale()) throw new DOMException('请求已停止', 'AbortError'); throw error; }
  try {
    const value = await readJson<T>(response);
    if (stale()) throw new DOMException('请求已停止', 'AbortError');
    if (!value || typeof value !== 'object') throw new ApiError(0);
    validateReceipt(value as Record<string, unknown>, intent);
    return value;
  } catch (error) {
    if (stale() || (error instanceof Error && error.name === 'AbortError')) throw error;
    throw new ApiError(0);
  }
}

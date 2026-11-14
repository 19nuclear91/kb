import { ApiError, encodeJson, isAbort } from '../api';
import type { Session } from '../session';
import { GAP_BODY_LIMIT, gapCursor, gapId, gapKeys, gapLimit, gapRequestId, gapStrip } from './input';
import { isGapIntent, type GapIntent } from './intents';
import { gapDetailValue, gapDocument, gapEnvelope, gapHistoryValue, gapItem, gapPageValue, gapParticipant, gapReceiptValue } from './protocol';
import { gapStates, type GapDetail, type GapDocument, type GapHistoryItem, type GapItem, type GapListQuery, type GapPage,
  type GapPageQuery, type GapParticipant, type GapReceipt, type GapSearch } from './types';

export class GapError extends ApiError {
  constructor(status: number) {
    super(status);
    if (status === 413) this.message = '问题请求超过大小限制，请缩短输入后重新确认。';
    if (status === 409) this.message = '问题状态、方案、候选或容量已变化，请重读事实后重新确认。';
  }
}
const base = '/api/knowledge-gaps', admin = '/api/admin/knowledge-gaps';
function pageQuery(query: GapPageQuery): string[] {
  const limit = gapLimit(query.limit), cursor = gapCursor(query.cursor);
  return [`limit=${limit}`, ...(cursor ? [`cursor=${encodeURIComponent(cursor)}`] : [])];
}
function listPath(query: GapListQuery, queue: boolean): string {
  if (!gapKeys(query, [], queue ? ['status', 'limit', 'cursor'] : ['view', 'status', 'limit', 'cursor'])) throw new GapError(400);
  const parts = pageQuery(query);
  if (!queue) {
    const view = query.view ?? 'mine';
    if (view !== 'mine' && view !== 'assigned') throw new GapError(400);
    parts.unshift(`view=${view}`);
  }
  const status = query.status;
  if (status != null) {
    if (typeof status !== 'string' || !gapStates.some(state => state === status)) throw new GapError(400);
    parts.push(`status=${status}`);
  }
  return `${queue ? admin : base}?${parts.join('&')}`;
}
export const gapPaths = {
  list: (query: GapListQuery = {}) => listPath(query, false),
  queue: (query: Omit<GapListQuery, 'view'> = {}) => listPath(query, true),
  detail: (id: string) => `${base}/${gapId(id)}`,
  history: (id: string, query: GapPageQuery = {}) => {
    if (!gapKeys(query, [], ['limit', 'cursor'])) throw new GapError(400);
    return `${base}/${gapId(id)}/history?${pageQuery(query).join('&')}`;
  },
  assignees: (id: string) => `${admin}/${gapId(id)}/assignees/search`,
  documents: (id: string) => `${base}/${gapId(id)}/solution-documents`,
};
function searchBody(query: GapSearch): Record<string, unknown> {
  if (!gapKeys(query, [], ['nameQuery', 'limit', 'cursor'])) throw new GapError(400);
  const name = query.nameQuery == null ? '' : gapStrip(query.nameQuery);
  if ([...name].length > 128) throw new GapError(400);
  return { nameQuery: name || null, limit: gapLimit(query.limit), cursor: gapCursor(query.cursor) };
}
export function requireGapSession(session: Session): void {
  const state = session.getSnapshot();
  if (state.phase === 'anonymous') throw new GapError(401);
  if (state.phase === 'password') throw new GapError(403);
  if (state.phase !== 'ready' || !state.me || state.me.mustChangePassword || state.me.user.enabled !== true) throw new GapError(503);
}
/** 有界、严格响应解码；只显示本地错误，不读取失败体中的正文或原始异常。 */
async function responseData(response: Response, limit: number): Promise<unknown> {
  if (!response.headers.get('Content-Type')?.includes('application/json') || !response.body) {
    await response.body?.cancel().catch(() => {}); throw new GapError(503);
  }
  const reader = response.body.getReader(), chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.byteLength;
      if (size > limit) { await reader.cancel().catch(() => {}); throw new GapError(503); }
      chunks.push(next.value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return gapEnvelope(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
}
async function call<T>(session: Session, path: string, options: RequestInit, validate: (value: unknown, status: number) => T,
  write = false, responseLimit = 4 * 1024 * 1024): Promise<T> {
  requireGapSession(session);
  const epoch = session.getSnapshot().epoch, response = await session.send(path, options);
  const stale = () => options.signal?.aborted || epoch !== session.getSnapshot().epoch || session.getSnapshot().phase !== 'ready';
  if (stale()) { await response.body?.cancel().catch(() => {}); throw new DOMException('请求已停止', 'AbortError'); }
  if (!response.ok) { await response.body?.cancel().catch(() => {}); throw new GapError(response.status); }
  try {
    if (write ? response.status !== 200 && response.status !== 201 : response.status !== 200) throw new GapError(503);
    const value = await responseData(response, responseLimit);
    if (stale()) throw new DOMException('请求已停止', 'AbortError');
    return validate(value, response.status);
  } catch (error) {
    if (stale() || isAbort(error)) throw new DOMException('请求已停止', 'AbortError');
    throw new GapError(write ? 0 : 503);
  }
}
export function gapList(session: Session, query: GapListQuery, signal: AbortSignal): Promise<GapPage<GapItem>> {
  const path = gapPaths.list(query), limit = gapLimit(query.limit);
  return call(session, path, { signal }, value => gapPageValue(value, limit, gapItem, item => item.gapId));
}
export function gapQueue(session: Session, query: Omit<GapListQuery, 'view'>, signal: AbortSignal): Promise<GapPage<GapItem>> {
  const path = gapPaths.queue(query), limit = gapLimit(query.limit);
  return call(session, path, { signal }, value => gapPageValue(value, limit, gapItem, item => item.gapId));
}
export function gapDetail(session: Session, id: string, signal: AbortSignal): Promise<GapDetail> {
  const target = gapId(id);
  return call(session, gapPaths.detail(target), { signal }, value => gapDetailValue(value, target));
}
export function gapHistory(session: Session, id: string, query: GapPageQuery, signal: AbortSignal): Promise<GapPage<GapHistoryItem>> {
  const path = gapPaths.history(id, query), limit = gapLimit(query.limit);
  return call(session, path, { signal }, value => gapPageValue(value, limit, gapHistoryValue, item => item.eventId));
}
export function gapAssignees(session: Session, id: string, query: GapSearch, signal: AbortSignal): Promise<GapPage<GapParticipant>> {
  const path = gapPaths.assignees(id), body = searchBody(query);
  return call(session, path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: encodeJson(body), signal },
    value => gapPageValue(value, body.limit as number, gapParticipant, item => item.userId));
}
export function gapDocuments(session: Session, id: string, query: GapSearch, signal: AbortSignal): Promise<GapPage<GapDocument>> {
  const path = gapPaths.documents(id), body = searchBody(query);
  return call(session, path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: encodeJson(body), signal },
    value => gapPageValue(value, body.limit as number, gapDocument, item => `${item.documentId}:${item.versionId}`));
}
/** 修改只发送一次；成功但回执不可靠为结果未知，必须由调用方显式核对/匹配重试。 */
export function gapWrite(session: Session, intent: GapIntent, requestId: string, signal: AbortSignal): Promise<GapReceipt> {
  if (!isGapIntent(intent)) throw new GapError(400);
  const id = gapRequestId(requestId);
  return call(session, intent.path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Request-Id': id },
    body: encodeJson(intent.body), signal }, (value, status) => gapReceiptValue(value, intent, status), true, GAP_BODY_LIMIT);
}

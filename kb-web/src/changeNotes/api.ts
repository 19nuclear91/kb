import { ApiError, decodeJson, encodeJson, positiveId, readJson } from '../api';
import { exactVersion, type WriteIntent } from '../admin/api';
import type { Version } from '../admin/types';
import type { Session } from '../session';

export interface Pair { documentId: string; versionId: string }
export interface Note extends Pair {
  hasNote: boolean; text: string | null; baseVersionId: string | null; baseVersionNo: number | null;
  corrected: boolean; createdAfterVisible: boolean; noteVersion: Version; writeBaseVersionId: string | null;
  writeBaseVersionNo: number | null; editable: boolean; reviewFrozen: boolean; visibleToReaders: boolean;
}
export interface HistoryItem extends Pair { id: string; changedAt: string; textBefore: string | null; textAfter: string | null;
  baseBefore: string | null; baseAfter: string | null; visibleToReaders: boolean; reason: string | null }
export interface History { items: HistoryItem[]; nextCursor: string | null; hasMore: boolean }
export interface Receipt extends Pair { noteVersion: Version; corrected: boolean; createdAfterVisible: boolean; updatedAt: string; replayed: boolean }
export const broken = () => new ApiError(503);
export function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw broken();
  return value as Record<string, unknown>;
}
export function text(value: unknown): string {
  if (typeof value !== 'string' || value.includes('\0') || /[\uD800-\uDFFF]/u.test(value)) throw broken();
  return value;
}
export function bool(value: unknown): boolean { if (typeof value !== 'boolean') throw broken(); return value; }
export function id(value: unknown): string {
  try { if (typeof value !== 'string') throw broken(); return positiveId(value); } catch { throw broken(); }
}
export function integer(value: unknown, minimum = 0): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) throw broken();
  return value;
}
export function enumeration<T extends string>(value: unknown, allowed: readonly T[]): T {
  if (typeof value !== 'string' || !allowed.includes(value as T)) throw broken(); return value as T;
}
export const nullableText = (value: unknown) => value === null ? null : text(value);
export const nullableId = (value: unknown) => value === null ? null : id(value);
const historyBase = (value: unknown) => value === null ? null : id(typeof value === 'bigint'
  || typeof value === 'number' && Number.isSafeInteger(value) ? String(value) : value);
const nullableVersionNo = (value: unknown) => value === null ? null : integer(value, 1);
export function version(value: unknown): Version {
  try { exactVersion(value as Version); return value as Version; } catch { throw broken(); }
}
export function instant(value: unknown): string {
  const raw = text(value); if (!/(Z|[+-]\d\d:\d\d)$/.test(raw) || !Number.isFinite(Date.parse(raw))) throw broken(); return raw;
}
export function decodeNote(value: unknown): Note {
  const row = object(value);
  const note: Note = { documentId: id(row.documentId), versionId: id(row.versionId), hasNote: bool(row.hasNote), text: nullableText(row.text),
    baseVersionId: nullableId(row.baseVersionId), baseVersionNo: nullableVersionNo(row.baseVersionNo), corrected: bool(row.corrected),
    createdAfterVisible: bool(row.createdAfterVisible), noteVersion: version(row.noteVersion), writeBaseVersionId: nullableId(row.writeBaseVersionId),
    writeBaseVersionNo: nullableVersionNo(row.writeBaseVersionNo), editable: bool(row.editable), reviewFrozen: bool(row.reviewFrozen), visibleToReaders: bool(row.visibleToReaders) };
  if (note.hasNote !== (note.text !== null) || note.hasNote !== (note.baseVersionId !== null)
    || (note.baseVersionId === null) !== (note.baseVersionNo === null)
    || (note.writeBaseVersionId === null) !== (note.writeBaseVersionNo === null)) throw broken();
  return note;
}
export function decodeHistory(value: unknown): History {
  const row = object(value);
  if (!Array.isArray(row.items) || row.items.length > 100) throw broken();
  const nextCursor = nullableText(row.nextCursor);
  if (nextCursor !== null && !/^[A-Za-z0-9_-]{1,4096}$/.test(nextCursor)) throw broken();
  return { items: row.items.map(value => { const item = object(value); return { id: id(item.id), documentId: id(item.documentId),
    versionId: id(item.versionId), changedAt: instant(item.changedAt), textBefore: nullableText(item.textBefore), textAfter: nullableText(item.textAfter),
    baseBefore: historyBase(item.baseBefore), baseAfter: historyBase(item.baseAfter), visibleToReaders: bool(item.visibleToReaders), reason: nullableText(item.reason) }; }),
    nextCursor, hasMore: nextCursor !== null };
}
const rules = {
  INVALID_INPUT: [400, '说明输入不符合要求。'], UNAUTHORIZED: [401, '登录状态已失效。'], FORBIDDEN: [403, '当前不能修改说明。'],
  PASSWORD_CHANGE_REQUIRED: [403, '请先修改密码。'], NOT_FOUND: [404, '版本不存在或当前不可查看。'],
  REVIEW_IN_PROGRESS: [409, '审核期间变更说明已冻结，修改需先撤回。'], REASON_REQUIRED: [409, '版本已发布，请重新读取并填写更正原因。'],
  NOTE_REMOVAL_NOT_ALLOWED: [409, '已发布版本的说明不能清除。'], NO_BASE_VERSION: [409, '没有更早的已发布版本，不能写变更说明。'],
  REQUEST_MISMATCH: [409, '请求与原绑定不匹配。'], NOTE_VERSION_CONFLICT: [409, '说明已被他人修改，请重新读取后编辑。'],
  NO_CHANGE: [409, '说明内容没有变化。'], PAYLOAD_TOO_LARGE: [413, '请求超过 32 KiB。'], UNAVAILABLE: [503, '变更说明暂不可确认。'],
} as const;
export class ChangeNoteError extends ApiError {
  constructor(public readonly reason: keyof typeof rules) { super(rules[reason][0]); this.message = rules[reason][1]; }
}
async function failure(response: Response): Promise<ApiError> {
  if (!response.body || !response.headers.get('Content-Type')?.includes('application/json')) {
    await response.body?.cancel().catch(() => {}); return new ApiError(response.status);
  }
  const reader = response.body.getReader(), chunks: Uint8Array[] = []; let size = 0;
  try {
    for (;;) { const next = await reader.read(); if (next.done) break; size += next.value.length;
      if (size > 8192) { await reader.cancel(); return new ApiError(response.status); } chunks.push(next.value); }
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    const row = object(decodeJson(new TextDecoder('utf-8', { fatal: true }).decode(bytes))), reason = row.reasonCode;
    if (row.code === 500 && row.data === null && typeof reason === 'string' && Object.hasOwn(rules, reason)
      && rules[reason as keyof typeof rules][0] === response.status) return new ChangeNoteError(reason as keyof typeof rules);
  } catch (error) { if (error instanceof Error && error.name === 'AbortError') throw error; }
  finally { reader.releaseLock(); }
  return new ApiError(response.status);
}
const path = (pair: Pair) => `/api/documents/${positiveId(pair.documentId)}/versions/${positiveId(pair.versionId)}/change-note`;
async function call(session: Session, path: string, init: RequestInit = {}): Promise<unknown> {
  const epoch = session.getSnapshot().epoch, response = await session.send(path, init);
  if (!response.ok) throw await failure(response);
  const value = await readJson<unknown>(response);
  if (init.signal?.aborted || epoch !== session.getSnapshot().epoch) throw new DOMException('请求已停止', 'AbortError');
  return value;
}
export const changeNotesApi = {
  get: async (session: Session, pair: Pair, signal?: AbortSignal) => {
    const result = decodeNote(await call(session, path(pair), { signal }));
    if (result.documentId !== pair.documentId || result.versionId !== pair.versionId) throw broken(); return result;
  },
  history: async (session: Session, pair: Pair, cursor: string | null, signal?: AbortSignal) => {
    const query = new URLSearchParams({ limit: '20', ...(cursor ? { cursor } : {}) });
    const result = decodeHistory(await call(session, `${path(pair)}/history?${query}`, { signal }));
    if (result.items.some(item => item.documentId !== pair.documentId || item.versionId !== pair.versionId)) throw broken(); return result;
  },
};
export function changeNoteIntent(note: Note, value: string | null, reason: string | null): WriteIntent {
  const clean = (raw: string | null) => { if (raw === null) return null; const value = raw.trim();
    if (!value || [...value].length > 2000 || /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F\uD800-\uDFFF]/u.test(value)) throw new ApiError(400); return value; };
  return { method: 'PUT', path: path(note), body: { text: clean(value), reason: clean(reason), expectedNoteVersion: exactVersion(note.noteVersion) } };
}
export async function changeNoteWrite<T>(session: Session, intent: WriteIntent, requestId: string, signal?: AbortSignal): Promise<T> {
  const body = encodeJson(intent.body); if (new TextEncoder().encode(body).length > 32768) throw new ApiError(413);
  const row = object(await call(session, intent.path, { method: 'PUT', signal, body, headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId } }));
  const receipt: Receipt = { documentId: id(row.documentId), versionId: id(row.versionId), noteVersion: version(row.noteVersion),
    corrected: bool(row.corrected), createdAfterVisible: bool(row.createdAfterVisible), updatedAt: instant(row.updatedAt), replayed: bool(row.replayed) };
  if (receipt.documentId !== intent.path.split('/')[3] || receipt.versionId !== intent.path.split('/')[5]
    || exactVersion(receipt.noteVersion) !== exactVersion(intent.body!.expectedNoteVersion as Version) + 1n) throw broken();
  return receipt as T;
}

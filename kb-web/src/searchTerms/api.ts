import { ApiError, decodeJson, encodeJson, positiveId, readJson, isAbort } from '../api';
import { numericId, type WriteIntent } from '../admin/api';
import type { Session } from '../session';

export const termBase = '/api/admin/search-terms';
export const aliasKinds = ['SYNONYM', 'ABBREVIATION', 'FORMER_NAME'] as const;
export type TermKind = 'TERM' | 'CORRECTION';
export interface Alias { text: string; kind: typeof aliasKinds[number]; ambiguous: boolean }
export interface Target { termId: string; canonicalName: string; description: string; enabled: boolean; version: string }
export interface Term extends Target { canonicalAmbiguous: boolean; aliases: Alias[]; createdAt: string; updatedAt: string }
export interface Correction { correctionId: string; wrongExpression: string; target: Target; enabled: boolean; available: boolean; version: string; createdAt: string; updatedAt: string }
export interface TermPage<T> { items: T[]; hasMore: boolean; nextCursor: string | null; dictionaryRevision: string }
export interface DirectoryFilters { nameQuery: string; enabled?: boolean; limit: number }
export interface TermReceipt { kind: TermKind; id: string; version: string; dictionaryRevision: string; replayed: boolean }
export interface Meaning { termId: string; canonicalName: string; description: string; version: string }
export interface TermDraft { canonicalName: string; description: string; aliases: { text: string; kind: typeof aliasKinds[number] }[] }
export const termReasons = {
  INVALID_INPUT: [400, '词典输入不符合要求，请检查表单。'], PAYLOAD_TOO_LARGE: [413, '词典请求过大。'],
  NAME_CONFLICT: [409, '标准名称已被占用，停用名称仍保留。'], EXPRESSION_CONFLICT: [409, '错词与已有名称或别名冲突。'],
  TARGET_UNAVAILABLE: [409, '目标术语已停用或不可选，请重新读取并选择。'], VERSION_CONFLICT: [409, '词条已变化，请重新读取后编辑。'],
  REQUEST_MISMATCH: [409, '请求标识与原意图不匹配。'], NO_CHANGE: [409, '词条没有实际变化。'], DICTIONARY_CHANGED: [409, '词典已变化，请从第一页重新读取。'],
  FORBIDDEN: [403, '当前没有词典操作资格。'], NOT_FOUND: [404, '词典记录不存在或不可访问。'], UNAUTHORIZED: [401, '登录状态已失效。'],
  UNAVAILABLE: [503, '词典服务暂不可用，结果尚未确认。'], INTERNAL_ERROR: [500, '词典请求未能完成，请主动核对。'],
} as const;
export class TermError extends ApiError {
  constructor(status: number, public readonly reason?: keyof typeof termReasons) { super(status); if (reason) this.message = termReasons[reason][1]; }
}
const broken = () => new TermError(503);
function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw broken(); return value as Record<string, unknown>; }
function text(value: unknown): string { if (typeof value !== 'string') throw broken(); return value; }
function bool(value: unknown): boolean { if (typeof value !== 'boolean') throw broken(); return value; }
export function termVersion(value: unknown): string {
  if (typeof value !== 'string' || !/^(0|[1-9]\d{0,18})$/.test(value) || BigInt(value) > 9223372036854775807n) throw broken(); return value;
}
function id(value: unknown): string { if (typeof value !== 'string') throw broken(); try { return positiveId(value); } catch { throw broken(); } }
export function termText(value: string, maximum = 64, required = true): string {
  const result = value.replace(/^[\p{Zs}\p{Zl}\p{Zp}\u0009-\u000d\u001c-\u001f]+|[\p{Zs}\p{Zl}\p{Zp}\u0009-\u000d\u001c-\u001f]+$/gu, '').normalize('NFC');
  if ((required && !result) || [...result].length > maximum || /[\p{Cc}\p{Cs}]/u.test(result)) throw new TermError(400, 'INVALID_INPUT'); return result;
}
const key = (value: string) => value.replace(/[A-Z]/g, character => character.toLowerCase());
function target(value: unknown): Target {
  const row = object(value); return { termId: id(row.termId), canonicalName: text(row.canonicalName), description: text(row.description), enabled: bool(row.enabled), version: termVersion(row.version) };
}
export function decodeTerm(value: unknown): Term {
  const row = object(value);
  if (!Array.isArray(row.aliases) || row.aliases.length > 20) throw broken();
  const aliases = row.aliases.map(value => { const item = object(value), kind = item.kind; if (!aliasKinds.includes(kind as Alias['kind'])) throw broken(); return { text: text(item.text), kind: kind as Alias['kind'], ambiguous: bool(item.ambiguous) }; });
  return { ...target(row), canonicalAmbiguous: bool(row.canonicalAmbiguous), aliases, createdAt: text(row.createdAt), updatedAt: text(row.updatedAt) };
}
export function decodeCorrection(value: unknown): Correction {
  const row = object(value), destination = target(row.target), enabled = bool(row.enabled), available = bool(row.available);
  if (available !== (enabled && destination.enabled)) throw broken();
  return { correctionId: id(row.correctionId), wrongExpression: text(row.wrongExpression), target: destination, enabled, available,
    version: termVersion(row.version), createdAt: text(row.createdAt), updatedAt: text(row.updatedAt) };
}
export function decodeReceipt(value: unknown): TermReceipt {
  const row = object(value); if (row.kind !== 'TERM' && row.kind !== 'CORRECTION') throw broken();
  return { kind: row.kind, id: id(row.id), version: termVersion(row.version), dictionaryRevision: termVersion(row.dictionaryRevision), replayed: bool(row.replayed) };
}
function page<T>(value: unknown, decode: (value: unknown) => T, limit: number): TermPage<T> {
  const row = object(value), hasMore = bool(row.hasMore), nextCursor = row.nextCursor === null ? null : text(row.nextCursor);
  if (!Array.isArray(row.items) || row.items.length > limit || (hasMore ? !nextCursor || nextCursor.length > 4096 || row.items.length === 0 : nextCursor !== null)) throw broken();
  return { items: row.items.map(decode), hasMore, nextCursor, dictionaryRevision: termVersion(row.dictionaryRevision) };
}
export function directoryBody(filters: DirectoryFilters, cursor: string | null) {
  if (!Number.isInteger(filters.limit) || filters.limit < 1 || filters.limit > 100 || (filters.enabled !== undefined && typeof filters.enabled !== 'boolean') || (cursor !== null && (!cursor || cursor.length > 4096))) throw new TermError(400, 'INVALID_INPUT');
  return { nameQuery: termText(filters.nameQuery, 64, false), ...(filters.enabled !== undefined ? { enabled: filters.enabled } : {}), limit: filters.limit, ...(cursor ? { cursor } : {}) };
}
async function failure(response: Response): Promise<ApiError> {
  if (response.status === 403) {
    void response.body?.cancel().catch(() => {});
    return new TermError(403, 'FORBIDDEN');
  }
  let reason: keyof typeof termReasons | undefined;
  if (response.body && response.headers.get('Content-Type')?.includes('application/json')) {
    const reader = response.body.getReader(), chunks: Uint8Array[] = []; let size = 0;
    try {
      for (;;) { const next = await reader.read(); if (next.done) break; size += next.value.byteLength; if (size > 8192) { await reader.cancel(); break; } chunks.push(next.value); }
      if (size <= 8192) {
        const bytes = new Uint8Array(size); let offset = 0; chunks.forEach(chunk => { bytes.set(chunk, offset); offset += chunk.length; });
        const value = object(decodeJson(new TextDecoder('utf-8', { fatal: true }).decode(bytes)));
        if (value.code === 500 && value.data === null && typeof value.reasonCode === 'string' && Object.hasOwn(termReasons, value.reasonCode)
          && termReasons[value.reasonCode as keyof typeof termReasons][0] === response.status) reason = value.reasonCode as keyof typeof termReasons;
      }
    } catch (error) { if (isAbort(error)) throw error; } finally { reader.releaseLock(); }
  } else { await response.body?.cancel().catch(() => {}); }
  return new TermError(response.status, reason);
}
async function call(session: Session, path: string, init: RequestInit): Promise<unknown> {
  const epoch = session.getSnapshot().epoch;
  const current = () => { if (init.signal?.aborted || epoch !== session.getSnapshot().epoch) throw new DOMException('请求已停止', 'AbortError'); };
  const response = await session.send(path, init, false, false);
  try { current(); } catch (error) { void response.body?.cancel().catch(() => {}); throw error; }
  if (!response.ok) { const error = await failure(response); current(); throw error; }
  const value = await readJson<unknown>(response); current(); return value;
}
const post = (body: unknown, signal?: AbortSignal): RequestInit => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: encodeJson(body), signal });
const directoryPath = (kind: TermKind) => `${termBase}/${kind === 'TERM' ? 'terms' : 'corrections'}`;
export const termApi = {
  async query(session: Session, kind: TermKind, filters: DirectoryFilters, cursor: string | null, signal?: AbortSignal): Promise<TermPage<Term | Correction>> {
    return page(await call(session, `${directoryPath(kind)}/query`, post(directoryBody(filters, cursor), signal)), kind === 'TERM' ? decodeTerm : decodeCorrection as (value: unknown) => Term | Correction, filters.limit);
  },
  async detail(session: Session, kind: TermKind, identity: string, signal?: AbortSignal): Promise<Term | Correction> {
    const value = object(await call(session, `${directoryPath(kind)}/${positiveId(identity)}`, { signal })); termVersion(value.dictionaryRevision);
    const row = kind === 'TERM' ? decodeTerm(value.term) : decodeCorrection(value.correction);
    if ((kind === 'TERM' ? (row as Term).termId : (row as Correction).correctionId) !== identity) throw broken(); return row;
  },
  async meanings(session: Session, termId: string, expression: string, cursor: string | null, signal?: AbortSignal): Promise<TermPage<Meaning>> {
    return page(await call(session, `${termBase}/terms/${positiveId(termId)}/meanings/query`, post({ expression: termText(expression), limit: 20, ...(cursor ? { cursor } : {}) }, signal)), value => {
      const row = object(value); return { termId: id(row.termId), canonicalName: text(row.canonicalName), description: text(row.description), version: termVersion(row.version) };
    }, 20);
  },
  async receipt(session: Session, requestId: string, signal?: AbortSignal): Promise<TermReceipt> { return decodeReceipt(await call(session, `${termBase}/commands/query`, post({ requestId }, signal))); },
};
export const termWrites = {
  term(current: Term | null, draft: TermDraft): WriteIntent {
    const canonicalName = termText(draft.canonicalName), description = termText(draft.description, 256), used = new Set([key(canonicalName)]);
    if (draft.aliases.length > 20) throw new TermError(400, 'INVALID_INPUT');
    const aliases = draft.aliases.map(alias => {
      const text = termText(alias.text), normalized = key(text); if (!aliasKinds.includes(alias.kind) || used.has(normalized)) throw new TermError(400, 'INVALID_INPUT'); used.add(normalized); return { text, kind: alias.kind };
    }).sort((a, b) => key(a.text) < key(b.text) ? -1 : key(a.text) > key(b.text) ? 1 : 0);
    return { method: current ? 'PUT' : 'POST', path: `${directoryPath('TERM')}${current ? `/${positiveId(current.termId)}` : ''}`,
      body: { canonicalName, description, aliases, ...(current ? { expectedVersion: BigInt(termVersion(current.version)) } : {}) } };
  },
  correction(current: Correction | null, wrongExpression: string, target: Target | null): WriteIntent {
    if (!target?.enabled) throw new TermError(409, 'TARGET_UNAVAILABLE');
    return { method: current ? 'PUT' : 'POST', path: `${directoryPath('CORRECTION')}${current ? `/${positiveId(current.correctionId)}` : ''}`,
      body: { wrongExpression: termText(wrongExpression), termId: numericId(target.termId), ...(current ? { expectedVersion: BigInt(termVersion(current.version)) } : {}) } };
  },
  enabled(kind: TermKind, identity: string, version: string, enabled: boolean): WriteIntent {
    return { method: 'PATCH', path: `${directoryPath(kind)}/${positiveId(identity)}/enabled`, body: { enabled, expectedVersion: BigInt(termVersion(version)) } };
  },
};
export async function termWrite<T>(session: Session, intent: WriteIntent, requestId: string, signal?: AbortSignal): Promise<T> {
  if (!/^[\x21-\x7e]{1,128}$/.test(requestId) || !intent.path.startsWith(termBase + '/')) throw new TermError(400, 'INVALID_INPUT');
  let value: unknown;
  try { value = await call(session, intent.path, { method: intent.method, headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId }, body: encodeJson(intent.body), signal }); }
  catch (error) { throw error; }
  try {
    const receipt = decodeReceipt(value), parts = intent.path.substring(termBase.length).split('/');
    if (receipt.kind !== (parts[1] === 'terms' ? 'TERM' : 'CORRECTION') || (parts[2] && receipt.id !== parts[2])) throw broken();
    return receipt as T;
  } catch { throw new ApiError(0); }
}

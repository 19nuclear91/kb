import { ApiError, decodeJson, encodeJson, positiveId, readJson } from '../api';
import { exactVersion, numericId, type WriteIntent } from '../admin/api';
import type { Version } from '../admin/types';
import type { Session } from '../session';

export const applicabilityStates = ['UNDECLARED', 'NOT_YET_EFFECTIVE', 'EXPIRED', 'EXPIRING_SOON', 'EFFECTIVE'] as const;
export const stewardshipStates = ['UNASSIGNED', 'NEEDS_REASSIGN', 'OVERDUE', 'DUE_SOON', 'OK'] as const;
export type ApplicabilityState = typeof applicabilityStates[number];
export type StewardshipState = typeof stewardshipStates[number];
export type UntilKind = 'UNDECLARED' | 'OPEN_ENDED' | 'DATE';
export interface Pair { documentId: string; versionId: string }
export interface Declaration { effectiveFrom: string | null; untilKind: UntilKind; effectiveUntil: string | null }
export interface Badge extends Pair, Declaration { state: ApplicabilityState; corrected: boolean; current: boolean }
export interface Applicability extends Pair, Declaration { state: ApplicabilityState; corrected: boolean; applicabilityVersion: Version; asOfDate: string; canEdit: boolean; reviewFrozen: boolean; visibleToReaders: boolean }
export interface ApplicabilityDraft extends Declaration { reason: string | null; expectedApplicabilityVersion: Version }
export interface Change extends Pair { id: string; changedAt: string; beforeEffectiveFrom: string | null; beforeUntilKind: UntilKind; beforeEffectiveUntil: string | null; afterEffectiveFrom: string | null; afterUntilKind: UntilKind; afterEffectiveUntil: string | null; visibleToReaders: boolean; reason: string | null }
export interface Candidate { userId: string; displayName: string }
export interface Stewardship { documentId: string; spaceId: string; state: StewardshipState; responsible: Candidate | null; cycleMonths: number | null; firstAssignedOn: string | null; lastConfirmedOn: string | null; dueDate: string | null; stewardshipVersion: Version; asOfDate: string }
export interface MineItem { documentId: string; title: string; state: StewardshipState; dueDate: string; lastConfirmedOn: string | null; cycleMonths: number; stewardshipVersion: Version; attentionReasons: string[]; currentVersion: Badge | null }
export interface SpaceItem { documentId: string; state: StewardshipState; dueDate: string | null; responsible: Candidate | null }
export interface Page<T> { items: T[]; nextCursor: string | null; asOfDate: string; hasMore: boolean }
export interface Receipt { documentId: string; versionId?: string; applicabilityVersion?: Version; stewardshipVersion?: Version; dueDate?: string; lastConfirmedOn?: string | null; corrected?: boolean; updatedAt: string; asOfDate: string; replayed: boolean }
export const attentionReasons = ['REVIEW_DUE_SOON', 'REVIEW_OVERDUE', 'APPLICABILITY_EXPIRING_SOON', 'APPLICABILITY_EXPIRED'] as const;
const reasonRules = {
  INVALID_INPUT: [400, '请求参数无效。'], UNAUTHORIZED: [401, '登录状态已失效。'], FORBIDDEN: [403, '当前无法执行此操作。'],
  PASSWORD_CHANGE_REQUIRED: [403, '必须先完成密码修改。'], NOT_FOUND: [404, '目标不存在或当前不可访问。'],
  REVIEW_IN_PROGRESS: [409, '审核期间适用期已冻结，修改需先撤回。'], REASON_REQUIRED: [409, '版本已对读者可见，请重新读取并填写更正原因。'],
  REQUEST_MISMATCH: [409, '请求标识与原操作不匹配。'], NO_CHANGE: [409, '提交内容没有变化。'],
  APPLICABILITY_VERSION_CONFLICT: [409, '适用期已被其他操作修改，请重新读取。'], STEWARDSHIP_VERSION_CONFLICT: [409, '复审设置已变化，请重新读取。'],
  STEWARD_INELIGIBLE: [409, '所选人员当前不合格，请重新选择。'], SPACE_ARCHIVED: [409, '空间已归档，复审设置只读。'],
  DOCUMENT_ARCHIVED: [409, '文档已归档，复审设置只读。'], PAYLOAD_TOO_LARGE: [413, '请求体超过 16 KiB 限制。'], UNAVAILABLE: [503, '适用期或复审信息暂不可确认。'],
} as const;
export class ValidityError extends ApiError {
  constructor(public readonly reason: keyof typeof reasonRules) { super(reasonRules[reason][0]); this.message = reasonRules[reason][1]; }
}
const broken = () => new ApiError(503);
function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw broken(); return value as Record<string, unknown>; }
function text(value: unknown): string { if (typeof value !== 'string' || value.includes('\0') || /[\uD800-\uDFFF]/u.test(value)) throw broken(); return value; }
function bool(value: unknown): boolean { if (typeof value !== 'boolean') throw broken(); return value; }
function id(value: unknown): string { try { if (typeof value !== 'string') throw broken(); return positiveId(value); } catch { throw broken(); } }
function version(value: unknown): Version { try { exactVersion(value as Version); return value as Version; } catch { throw broken(); } }
function enumeration<T extends string>(value: unknown, allowed: readonly T[]): T { if (typeof value !== 'string' || !allowed.includes(value as T)) throw broken(); return value as T; }
export function calendarDate(value: unknown): string {
  const raw = text(value);
  if (!/^[12]\d{3}-\d{2}-\d{2}$/.test(raw) || raw < '1900-01-01' || raw > '2999-12-31') throw broken();
  const time = new Date(`${raw}T00:00:00Z`); if (!Number.isFinite(time.getTime()) || time.toISOString().slice(0, 10) !== raw) throw broken();
  return raw;
}
const nullableDate = (value: unknown) => value === null ? null : calendarDate(value);
function instant(value: unknown): string { const raw = text(value); if (!/(Z|[+-]\d\d:\d\d)$/.test(raw) || !Number.isFinite(Date.parse(raw))) throw broken(); return raw; }
function cycle(value: unknown): number { if (typeof value !== 'number' || ![3, 6, 12, 24].includes(value)) throw broken(); return value; }
function declaration(row: Record<string, unknown>): Declaration {
  const effectiveFrom = nullableDate(row.effectiveFrom), untilKind = enumeration(row.untilKind, ['UNDECLARED', 'OPEN_ENDED', 'DATE'] as const), effectiveUntil = nullableDate(row.effectiveUntil);
  if ((untilKind === 'DATE') !== (effectiveUntil !== null) || (effectiveFrom && effectiveUntil && effectiveUntil < effectiveFrom)) throw broken();
  return { effectiveFrom, untilKind, effectiveUntil };
}
export function decodeBadge(value: unknown): Badge {
  const row = object(value); return { documentId: id(row.documentId), versionId: id(row.versionId), ...declaration(row),
    state: enumeration(row.state, applicabilityStates), corrected: bool(row.corrected), current: bool(row.current) };
}
export function decodeApplicability(value: unknown): Applicability {
  const row = object(value); return { documentId: id(row.documentId), versionId: id(row.versionId), ...declaration(row),
    state: enumeration(row.state, applicabilityStates), corrected: bool(row.corrected), applicabilityVersion: version(row.applicabilityVersion),
    asOfDate: calendarDate(row.asOfDate), canEdit: bool(row.canEdit), reviewFrozen: bool(row.reviewFrozen), visibleToReaders: bool(row.visibleToReaders) };
}
function candidate(value: unknown): Candidate { const row = object(value); return { userId: id(row.userId), displayName: text(row.displayName) }; }
function page<T>(value: unknown, decode: (item: unknown) => T): Page<T> {
  const row = object(value); if (!Array.isArray(row.items) || row.items.length > 100) throw broken();
  const nextCursor = row.nextCursor === null ? null : text(row.nextCursor);
  if (nextCursor !== null && !/^[A-Za-z0-9_-]{1,4096}$/.test(nextCursor)) throw broken();
  return { items: row.items.map(decode), nextCursor, asOfDate: calendarDate(row.asOfDate), hasMore: nextCursor !== null };
}
export function decodeStewardship(value: unknown): Stewardship {
  const row = object(value); return { documentId: id(row.documentId), spaceId: id(row.spaceId), state: enumeration(row.state, stewardshipStates),
    responsible: row.responsible === null ? null : candidate(row.responsible), cycleMonths: row.cycleMonths === null ? null : cycle(row.cycleMonths),
    firstAssignedOn: nullableDate(row.firstAssignedOn), lastConfirmedOn: nullableDate(row.lastConfirmedOn), dueDate: nullableDate(row.dueDate),
    stewardshipVersion: version(row.stewardshipVersion), asOfDate: calendarDate(row.asOfDate) };
}
function mineItem(value: unknown): MineItem {
  const row = object(value); if (!Array.isArray(row.attentionReasons) || row.attentionReasons.length > 4) throw broken();
  return { documentId: id(row.documentId), title: text(row.title), state: enumeration(row.state, stewardshipStates), dueDate: calendarDate(row.dueDate),
    lastConfirmedOn: nullableDate(row.lastConfirmedOn), cycleMonths: cycle(row.cycleMonths), stewardshipVersion: version(row.stewardshipVersion),
    attentionReasons: row.attentionReasons.map(reason => enumeration(reason, attentionReasons)), currentVersion: row.currentVersion === null ? null : decodeBadge(row.currentVersion) };
}
function spaceItem(value: unknown): SpaceItem { const row = object(value); return { documentId: id(row.documentId), state: enumeration(row.state, stewardshipStates),
  dueDate: nullableDate(row.dueDate), responsible: row.responsible === null ? null : candidate(row.responsible) }; }
async function failure(response: Response): Promise<ApiError> {
  if (!response.body || !response.headers.get('Content-Type')?.includes('application/json')) { await response.body?.cancel().catch(() => {}); return new ApiError(response.status); }
  const reader = response.body.getReader(), parts: Uint8Array[] = []; let length = 0;
  try {
    for (;;) { const next = await reader.read(); if (next.done) break; length += next.value.length; if (length > 8192) { await reader.cancel(); return new ApiError(response.status); } parts.push(next.value); }
    const bytes = new Uint8Array(length); let offset = 0; for (const part of parts) { bytes.set(part, offset); offset += part.length; }
    const row = object(decodeJson(new TextDecoder('utf-8', { fatal: true }).decode(bytes))), reason = row.reasonCode;
    if (row.code === 500 && row.data === null && typeof reason === 'string' && Object.hasOwn(reasonRules, reason) && reasonRules[reason as keyof typeof reasonRules][0] === response.status)
      return new ValidityError(reason as keyof typeof reasonRules);
  } catch (error) { if (error instanceof Error && error.name === 'AbortError') throw error; }
  finally { reader.releaseLock(); }
  return new ApiError(response.status);
}
async function call(session: Session, path: string, body?: unknown, signal?: AbortSignal): Promise<unknown> {
  const epoch = session.getSnapshot().epoch;
  const response = await session.send(path, body === undefined ? { signal } : { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: encodeJson(body) });
  if (!response.ok) throw await failure(response);
  const value = await readJson<unknown>(response);
  if (signal?.aborted || epoch !== session.getSnapshot().epoch) throw new DOMException('请求已停止', 'AbortError');
  return value;
}
const params = (values: Record<string, string | null>) => { const query = new URLSearchParams(); for (const [key, value] of Object.entries(values)) if (value !== null) query.set(key, value); return `?${query}`; };
export const validityApi = {
  get: async (session: Session, pair: Pair, signal?: AbortSignal) => { const row = decodeApplicability(await call(session, `/api/documents/${positiveId(pair.documentId)}/versions/${positiveId(pair.versionId)}/applicability`, undefined, signal));
    if (row.documentId !== pair.documentId || row.versionId !== pair.versionId) throw broken(); return row; },
  batch: async (session: Session, pairs: Pair[], signal?: AbortSignal) => {
    if (!pairs.length || pairs.length > 100 || new Set(pairs.map(pairKey)).size !== pairs.length) throw new ApiError(400);
    const row = object(await call(session, '/api/documents/applicability/query', { items: pairs.map(pair => ({ documentId: numericId(pair.documentId), versionId: numericId(pair.versionId) })) }, signal));
    if (!Array.isArray(row.items) || row.items.length > pairs.length) throw broken();
    const items = row.items.map(decodeBadge), keys = new Set(pairs.map(pairKey));
    if (new Set(items.map(pairKey)).size !== items.length || items.some(item => !keys.has(pairKey(item)))) throw broken();
    return { items, asOfDate: calendarDate(row.asOfDate) };
  },
  history: async (session: Session, pair: Pair, cursor: string | null, signal?: AbortSignal) => page(await call(session,
    `/api/documents/${positiveId(pair.documentId)}/versions/${positiveId(pair.versionId)}/applicability/history${params({ cursor })}`, undefined, signal), value => {
      const row = object(value); return { id: id(row.id), documentId: id(row.documentId), versionId: id(row.versionId), changedAt: instant(row.changedAt),
        beforeEffectiveFrom: nullableDate(row.beforeEffectiveFrom), beforeUntilKind: enumeration(row.beforeUntilKind, ['UNDECLARED', 'OPEN_ENDED', 'DATE'] as const), beforeEffectiveUntil: nullableDate(row.beforeEffectiveUntil),
        afterEffectiveFrom: nullableDate(row.afterEffectiveFrom), afterUntilKind: enumeration(row.afterUntilKind, ['UNDECLARED', 'OPEN_ENDED', 'DATE'] as const), afterEffectiveUntil: nullableDate(row.afterEffectiveUntil),
        visibleToReaders: bool(row.visibleToReaders), reason: row.reason === null ? null : text(row.reason) } satisfies Change;
    }),
  stewardship: async (session: Session, document: string, signal?: AbortSignal) => { const row = decodeStewardship(await call(session, `/api/admin/documents/${positiveId(document)}/stewardship`, undefined, signal)); if (row.documentId !== document) throw broken(); return row; },
  candidates: async (session: Session, document: string, name: string, cursor: string | null, signal?: AbortSignal) => page(await call(session,
    `/api/admin/documents/${positiveId(document)}/stewardship/candidates`, { nameQuery: clean(name, 64, false), ...(cursor ? { cursor } : {}) }, signal), candidate),
  mine: async (session: Session, view: 'attention' | 'all', cursor: string | null, signal?: AbortSignal) => page(await call(session, `/api/documents/stewardship${params({ view, cursor })}`, undefined, signal), mineItem),
  count: async (session: Session, signal?: AbortSignal) => { const row = object(await call(session, '/api/documents/stewardship/count', undefined, signal));
    const count = text(row.count); if (!/^(0|[1-9]\d{0,18})$/.test(count)) throw broken(); return { count, asOfDate: calendarDate(row.asOfDate) }; },
  space: async (session: Session, space: string, state: StewardshipState, cursor: string | null, signal?: AbortSignal) => page(await call(session,
    `/api/admin/spaces/${positiveId(space)}/stewardship${params({ state, cursor })}`, undefined, signal), spaceItem),
};
function clean(value: string, max: number, required = true): string { const result = value.trim(); if ((required && !result) || [...result].length > max || /[\uD800-\uDFFF]/u.test(result) || result.includes('\0')) throw new ApiError(400); return result; }
export const validityWrites = {
  applicability: (pair: Pair, draft: ApplicabilityDraft): WriteIntent => {
    try { declaration(draft as unknown as Record<string, unknown>); } catch { throw new ApiError(400); }
    return { method: 'PUT', path: `/api/documents/${positiveId(pair.documentId)}/versions/${positiveId(pair.versionId)}/applicability`, body: { ...draft,
      reason: draft.reason === null ? null : clean(draft.reason, 2000), expectedApplicabilityVersion: exactVersion(draft.expectedApplicabilityVersion) } };
  },
  stewardship: (document: string, responsible: string | null, months: number | null, expected: Version): WriteIntent => {
    if ((responsible === null) !== (months === null) || (months !== null && ![3, 6, 12, 24].includes(months))) throw new ApiError(400);
    return { method: 'PUT', path: `/api/admin/documents/${positiveId(document)}/stewardship`, body: { responsibleUserId: responsible === null ? null : numericId(responsible), cycleMonths: months, expectedStewardshipVersion: exactVersion(expected) } };
  },
  confirm: (document: string, expected: Version): WriteIntent => ({ method: 'POST', path: `/api/documents/${positiveId(document)}/stewardship/confirm`, body: { expectedStewardshipVersion: exactVersion(expected) } }),
};
export async function validityWrite<T>(session: Session, intent: WriteIntent, requestId: string, signal?: AbortSignal): Promise<T> {
  const epoch = session.getSnapshot().epoch, body = encodeJson(intent.body);
  if (new TextEncoder().encode(body).length > 16384) throw new ApiError(413);
  const response = await session.send(intent.path, { method: intent.method, signal, headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId }, body });
  if (!response.ok) throw await failure(response);
  const row = object(await readJson<unknown>(response));
  if (signal?.aborted || epoch !== session.getSnapshot().epoch) throw new DOMException('请求已停止', 'AbortError');
  const receipt: Receipt = { documentId: id(row.documentId), updatedAt: instant(row.updatedAt), asOfDate: calendarDate(row.asOfDate), replayed: bool(row.replayed) };
  const applicability = intent.path.endsWith('/applicability'), document = intent.path.split('/')[applicability || intent.path.endsWith('/confirm') ? 3 : 4];
  if (receipt.documentId !== document) throw broken();
  if (applicability) { receipt.versionId = id(row.versionId); receipt.applicabilityVersion = version(row.applicabilityVersion); receipt.corrected = bool(row.corrected);
    if (receipt.versionId !== intent.path.split('/')[5] || exactVersion(receipt.applicabilityVersion) !== exactVersion(intent.body!.expectedApplicabilityVersion as Version) + 1n) throw broken();
  } else { receipt.stewardshipVersion = version(row.stewardshipVersion); receipt.dueDate = calendarDate(row.dueDate); receipt.lastConfirmedOn = nullableDate(row.lastConfirmedOn);
    if (exactVersion(receipt.stewardshipVersion) !== exactVersion(intent.body!.expectedStewardshipVersion as Version) + 1n) throw broken(); }
  return receipt as T;
}
export const pairKey = (pair: Pair) => `${positiveId(pair.documentId)}/${positiveId(pair.versionId)}`;
export function sourcePair(value: { docId?: string | null; documentId?: string | null; versionId?: string | null }): Pair | null {
  const documentId = value.docId ?? value.documentId; if (!documentId || !value.versionId) return null;
  try { return { documentId: positiveId(documentId), versionId: positiveId(value.versionId) }; } catch { return null; }
}
/** 每批最多 100 个；换目标或身份后，即便传输忽略 abort，旧响应仍被丢弃。 */
export class ValidityBatch {
  private generation = 0;
  private controller: AbortController | null = null;
  constructor(private readonly session: Session) {}
  clear() { this.generation++; this.controller?.abort(); this.controller = null; }
  async load(pairs: Pair[]): Promise<Map<string, Badge> | null> {
    this.clear(); const generation = this.generation, epoch = this.session.getSnapshot().epoch, controller = new AbortController(); this.controller = controller;
    const unique = [...new Map(pairs.map(pair => [pairKey(pair), pair])).values()], result = new Map<string, Badge>();
    try {
      for (let index = 0; index < unique.length; index += 100) {
        const page = await validityApi.batch(this.session, unique.slice(index, index + 100), controller.signal);
        if (generation !== this.generation || epoch !== this.session.getSnapshot().epoch || controller.signal.aborted) return null;
        for (const badge of page.items) result.set(pairKey(badge), badge);
      }
      return generation === this.generation ? result : null;
    } catch (error) { if (generation !== this.generation || controller.signal.aborted) return null; throw error; }
  }
}

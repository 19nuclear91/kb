import { ApiError, positiveId, readJson } from '../api';
import type { Session } from '../session';

export const healthSignals = ['STEWARD_UNASSIGNED', 'STEWARD_NEEDS_REASSIGN', 'REVIEW_OVERDUE', 'REVIEW_DUE_SOON', 'APPLICABILITY_EXPIRED', 'APPLICABILITY_EXPIRING_SOON'] as const;
export type HealthSignal = typeof healthSignals[number];
export type HealthFilter = 'ALL' | HealthSignal;
export const healthCountKeys = ['stewardUnassigned', 'stewardNeedsReassign', 'reviewOverdue', 'reviewDueSoon', 'applicabilityExpired', 'applicabilityExpiringSoon'] as const;
export const healthLabels: Record<HealthSignal, string> = {
  STEWARD_UNASSIGNED: '未指定责任人', STEWARD_NEEDS_REASSIGN: '责任人需重新指定', REVIEW_OVERDUE: '复审已逾期',
  REVIEW_DUE_SOON: '即将复审', APPLICABILITY_EXPIRED: '适用期已届满', APPLICABILITY_EXPIRING_SOON: '适用期即将届满',
};
export interface HealthItem {
  documentId: string; signals: HealthSignal[];
  stewardship: { state: 'UNASSIGNED' | 'NEEDS_REASSIGN' | 'OVERDUE' | 'DUE_SOON' | 'OK'; responsible: { userId: string; displayName: string } | null; dueDate: string | null };
  applicability: { versionId: string; state: 'EXPIRED' | 'EXPIRING_SOON'; effectiveFrom: string | null; effectiveUntil: string } | null;
}
export interface HealthReport {
  definitionVersion: 'KNOWLEDGE_HEALTH_V1'; spaceId: string; asOf: string; asOfDate: string; dayTimeZone: string;
  signal: HealthFilter; limit: number; counts: Record<typeof healthCountKeys[number], string>; affectedDocumentCount: string;
  items: HealthItem[]; hasMore: boolean; nextCursor: string | null;
}
export class HealthError extends ApiError {
  constructor(status: number, public readonly reason?: 'SPACE_ARCHIVED' | 'RADAR_DATE_CHANGED') {
    super(status);
    if (reason === 'SPACE_ARCHIVED') this.message = '空间已归档，诊断暂停。';
    if (reason === 'RADAR_DATE_CHANGED') this.message = '业务日期已变化，请从首页重新查询。';
  }
}
const broken = (): never => { throw new ApiError(503); };
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return broken();
  return value as Record<string, unknown>;
}
function shape(row: Record<string, unknown>, keys: string[]) {
  if (Object.keys(row).length !== keys.length || keys.some(key => !Object.hasOwn(row, key))) broken();
}
function text(value: unknown): string { return typeof value === 'string' ? value : broken(); }
function integer(value: unknown, positive = false): string {
  const result = text(value);
  if (!(positive ? /^[1-9]\d{0,18}$/ : /^(0|[1-9]\d{0,18})$/).test(result) || BigInt(result) > 9223372036854775807n) broken();
  return result;
}
function date(value: unknown): string {
  const result = text(value);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(result)) return broken();
  const parsed = new Date(`${result}T00:00:00Z`);
  if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== result) broken();
  return result;
}
const nullableDate = (value: unknown) => value === null ? null : date(value);
const days = (value: string) => Date.parse(`${value}T00:00:00Z`) / 86400000;
function item(value: unknown, today: string): HealthItem {
  const row = object(value); shape(row, ['documentId', 'signals', 'stewardship', 'applicability']);
  const documentId = integer(row.documentId, true), st = object(row.stewardship);
  shape(st, ['state', 'responsible', 'dueDate']);
  if (!['UNASSIGNED', 'NEEDS_REASSIGN', 'OVERDUE', 'DUE_SOON', 'OK'].includes(text(st.state))) broken();
  let responsible: HealthItem['stewardship']['responsible'] = null;
  if (st.responsible !== null) {
    const person = object(st.responsible); shape(person, ['userId', 'displayName']);
    responsible = { userId: integer(person.userId, true), displayName: text(person.displayName) };
  }
  const state = st.state as HealthItem['stewardship']['state'], dueDate = nullableDate(st.dueDate);
  if ((state === 'UNASSIGNED') !== (responsible === null)) broken();
  if (['OVERDUE', 'DUE_SOON', 'OK'].includes(state)) {
    if (dueDate === null) return broken();
    const offset = days(dueDate) - days(today), expected = offset < 0 ? 'OVERDUE' : offset <= 30 ? 'DUE_SOON' : 'OK';
    if (state !== expected) broken();
  }
  const expectedSignals: HealthSignal[] = [];
  const management = { UNASSIGNED: 'STEWARD_UNASSIGNED', NEEDS_REASSIGN: 'STEWARD_NEEDS_REASSIGN', OVERDUE: 'REVIEW_OVERDUE', DUE_SOON: 'REVIEW_DUE_SOON' } as const;
  if (state !== 'OK') expectedSignals.push(management[state]);
  let applicability: HealthItem['applicability'] = null;
  if (row.applicability !== null) {
    const a = object(row.applicability); shape(a, ['versionId', 'state', 'effectiveFrom', 'effectiveUntil']);
    const effectiveFrom = nullableDate(a.effectiveFrom), effectiveUntil = date(a.effectiveUntil);
    if (effectiveFrom !== null && (effectiveFrom > today || effectiveFrom > effectiveUntil)) broken();
    const offset = days(effectiveUntil) - days(today), expected = offset < 0 ? 'EXPIRED' : offset <= 30 ? 'EXPIRING_SOON' : null;
    if (expected === null || a.state !== expected) return broken();
    applicability = { versionId: integer(a.versionId, true), state: expected, effectiveFrom, effectiveUntil };
    expectedSignals.push(expected === 'EXPIRED' ? 'APPLICABILITY_EXPIRED' : 'APPLICABILITY_EXPIRING_SOON');
  }
  if (!expectedSignals.length || !Array.isArray(row.signals) || row.signals.length !== expectedSignals.length
      || row.signals.some((signal, index) => signal !== expectedSignals[index])) broken();
  return { documentId, signals: expectedSignals, stewardship: { state, responsible, dueDate }, applicability };
}
export function parseHealthReport(value: unknown, spaceId: string, signal: HealthFilter, limit = 20): HealthReport {
  const row = object(value);
  shape(row, ['definitionVersion', 'spaceId', 'asOf', 'asOfDate', 'dayTimeZone', 'signal', 'limit', 'counts', 'affectedDocumentCount', 'items', 'hasMore', 'nextCursor']);
  if (row.definitionVersion !== 'KNOWLEDGE_HEALTH_V1' || integer(row.spaceId, true) !== spaceId || row.signal !== signal || row.limit !== limit) broken();
  const asOf = text(row.asOf), asOfDate = date(row.asOfDate), dayTimeZone = text(row.dayTimeZone);
  if (!/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-](?:0\d|1[0-7]):[0-5]\d|[+-]18:00)$/.test(asOf) || !Number.isFinite(Date.parse(asOf))) broken();
  date(asOf.slice(0, 10));
  try {
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: dayTimeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(asOf));
    const part = (type: string) => parts.find(p => p.type === type)?.value;
    if (`${part('year')}-${part('month')}-${part('day')}` !== asOfDate) broken();
  } catch { return broken(); }
  const countsValue = object(row.counts); shape(countsValue, [...healthCountKeys]);
  const counts = Object.fromEntries(healthCountKeys.map(key => [key, integer(countsValue[key])])) as HealthReport['counts'];
  const affectedDocumentCount = integer(row.affectedDocumentCount), affected = BigInt(affectedDocumentCount);
  const values = Object.values(counts).map(BigInt), sum = values.reduce((a, b) => a + b, 0n);
  if (values.some(count => count > affected) || sum < affected || values.slice(0, 4).reduce((a, b) => a + b, 0n) > affected
      || values.slice(4).reduce((a, b) => a + b, 0n) > affected) broken();
  if (!Array.isArray(row.items) || row.items.length > limit || typeof row.hasMore !== 'boolean') return broken();
  const items = row.items.map(value => item(value, asOfDate));
  if (items.some((entry, index) => index > 0 && BigInt(entry.documentId) <= BigInt(items[index - 1].documentId))
      || signal !== 'ALL' && items.some(entry => !entry.signals.includes(signal)) || BigInt(items.length) > affected) broken();
  for (let index = 0; index < healthSignals.length; index++) {
    if (BigInt(items.filter(entry => entry.signals.includes(healthSignals[index])).length) > BigInt(counts[healthCountKeys[index]])) broken();
  }
  const nextCursor = row.nextCursor === null ? null : text(row.nextCursor);
  if (row.hasMore ? items.length !== limit || !nextCursor || nextCursor.length > 2048 || !/^[A-Za-z0-9_-]+$/.test(nextCursor) : nextCursor !== null) broken();
  const selectedCount = signal === 'ALL' ? affected : BigInt(counts[healthCountKeys[healthSignals.indexOf(signal)]]);
  if (row.hasMore && selectedCount <= BigInt(items.length)) broken();
  return { definitionVersion: 'KNOWLEDGE_HEALTH_V1', spaceId, asOf, asOfDate, dayTimeZone, signal, limit, counts, affectedDocumentCount, items, hasMore: row.hasMore, nextCursor };
}
export async function healthReport(session: Session, spaceId: string, filter: HealthFilter, cursor: string | null, signal: AbortSignal): Promise<HealthReport> {
  positiveId(spaceId);
  if (filter !== 'ALL' && !healthSignals.includes(filter) || cursor !== null && (!cursor || cursor.length > 2048)) throw new ApiError(400);
  const epoch = session.getSnapshot().epoch, query = new URLSearchParams({ signal: filter, limit: '20' });
  if (cursor !== null) query.set('cursor', cursor);
  const response = await session.send(`/api/admin/spaces/${spaceId}/health-radar?${query}`, { signal }, false, false);
  if (signal.aborted || epoch !== session.getSnapshot().epoch) { void response.body?.cancel().catch(() => {}); throw new DOMException('请求已停止', 'AbortError'); }
  if (!response.ok) {
    const reason = response.headers.get('X-Kb-Health-Error');
    await response.body?.cancel().catch(() => {});
    const status = response.status === 405 || response.status === 404 && reason !== 'NOT_FOUND' ? 503 : response.status;
    throw new HealthError(status, status === 409 && (reason === 'SPACE_ARCHIVED' || reason === 'RADAR_DATE_CHANGED') ? reason : undefined);
  }
  const value = await readJson<unknown>(response);
  if (signal.aborted || epoch !== session.getSnapshot().epoch) throw new DOMException('请求已停止', 'AbortError');
  const report = parseHealthReport(value, spaceId, filter);
  if (cursor === null) {
    const count = BigInt(filter === 'ALL' ? report.affectedDocumentCount : report.counts[healthCountKeys[healthSignals.indexOf(filter)]]);
    if (report.items.length !== Number(count > 20n ? 20n : count) || report.hasMore !== (count > 20n)) broken();
  }
  return report;
}

import { ApiError, positiveId, readJson } from '../api';
import type { Session } from '../session';

export type AuditScalar = string | boolean | number | bigint | null;
export type AuditValue = AuditScalar | string[];
export interface AuditMetadata {
  id: string; eventId: string; operationScope: string; requestId: string; actorUserId: string | null;
  targetType: string; targetId: string | null; operation: string; status: string; createdAt: string; attemptedUsername: string | null;
}
export interface AuditChange { field: string; before: AuditValue; after: AuditValue }
export interface AuditDetail { metadata: AuditMetadata; changes: AuditChange[]; summaryAvailable: boolean }
export interface AuditWindow { from: string; to: string }
export interface AuditPage extends AuditWindow { items: AuditMetadata[]; hasMore: boolean; nextCursor: string | null }
export interface AuditFilters {
  status?: string; operationScope?: string; operation?: string; actorUserId?: string; targetType?: string;
  targetId?: string; requestId?: string; loginUsername?: string; from?: string; to?: string; limit: number;
}
export const defaultAuditFilters = (): AuditFilters => ({ limit: 20 });
const base = '/api/admin/audits', maxLong = 9223372036854775807n, dayMicros = 86400000000n;
const uuidPattern = /^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i;
const limits = { operationScope: 128, operation: 128, targetType: 64, requestId: 256, loginUsername: 128 } as const;
const invalid = () => new ApiError(400);
const broken = () => new ApiError(503);
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw broken();
  return value as Record<string, unknown>;
}
function text(value: unknown): string { if (typeof value !== 'string') throw broken(); return value; }
function id(value: unknown): string {
  if (typeof value !== 'string') throw broken();
  try { return positiveId(value); } catch { throw broken(); }
}
function uuid(value: unknown): string {
  const result = text(value); if (!uuidPattern.test(result)) throw broken(); return result.toLowerCase();
}
export function validAuditCursor(value: unknown): value is string {
  return typeof value === 'string' && value.length <= 2731 && /^[A-Za-z0-9_-]+$/.test(value);
}

/** 时间计算保留微秒；显示格式转换不得参与请求或游标窗口构造。 */
export function auditInstant(value: string): bigint {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|([+-])(\d{2}):(\d{2}))$/.exec(value);
  if (!match) throw invalid();
  const [, year, month, day, hour, minute, second, fraction = '', zone, sign, zh = '0', zm = '0'] = match;
  const [y, m, d, h, mi, s, oh, om] = [year, month, day, hour, minute, second, zh, zm].map(Number);
  if (h > 23 || mi > 59 || s > 59 || oh > 18 || om > 59 || (oh === 18 && om !== 0)) throw invalid();
  const date = new Date(0); date.setUTCFullYear(y, m - 1, d); date.setUTCHours(h, mi, s, 0);
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) throw invalid();
  const offset = zone === 'Z' ? 0 : (sign === '-' ? -1 : 1) * (oh * 60 + om);
  return BigInt(date.getTime() - offset * 60000) * 1000n + BigInt(fraction.padEnd(6, '0').slice(0, 6));
}
export function checkAuditWindow(window: AuditWindow): void {
  const delta = auditInstant(window.to) - auditInstant(window.from);
  if (delta <= 0n || delta > 31n * dayMicros) throw invalid();
}
/** datetime-local 按设备本地时区解释，拒绝无效日期及被自动归一化的时刻。 */
export function auditLocalTime(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
  if (!match) throw invalid();
  const [y, m, d, h, mi, s] = match.slice(1).map(part => Number(part ?? '0'));
  if (y < 1 || h > 23 || mi > 59 || s > 59) throw invalid();
  const date = new Date(0); date.setFullYear(y, m - 1, d); date.setHours(h, mi, s, 0);
  if (date.getFullYear() !== y || date.getMonth() !== m - 1 || date.getDate() !== d || date.getHours() !== h || date.getMinutes() !== mi || date.getSeconds() !== s) throw invalid();
  return date.toISOString();
}
export function auditPath(filters: AuditFilters, cursor?: string | null): string {
  if (!Number.isInteger(filters.limit) || filters.limit < 1 || filters.limit > 100) throw invalid();
  const query = new URLSearchParams({ limit: String(filters.limit) });
  for (const [key, max] of Object.entries(limits) as [keyof typeof limits, number][]) {
    const value = filters[key];
    if (value === '' || value === undefined) continue;
    if (typeof value !== 'string' || value !== value.trim() || [...value].length > max || /[\u0000-\u001f\u007f-\u009f]/.test(value)) throw invalid();
    query.set(key, value);
  }
  for (const key of ['actorUserId', 'targetId'] as const) if (filters[key] !== undefined && filters[key] !== '') {
    if (typeof filters[key] !== 'string') throw invalid();
    query.set(key, positiveId(filters[key]));
  }
  if (query.has('targetId') && !query.has('targetType')) throw invalid();
  if (filters.status) {
    if (!['SUCCESS', 'FAILED'].includes(filters.status)) throw invalid();
    query.set('status', filters.status);
  }
  if (filters.from || filters.to) {
    if (!filters.from || !filters.to) throw invalid();
    checkAuditWindow({ from: filters.from, to: filters.to }); query.set('from', filters.from); query.set('to', filters.to);
  }
  if (cursor != null) { if (!validAuditCursor(cursor)) throw invalid(); query.set('cursor', cursor); }
  return `${base}?${query}`;
}
function metadata(value: unknown): AuditMetadata {
  const row = object(value), createdAt = text(row.createdAt);
  try { auditInstant(createdAt); } catch { throw broken(); }
  return { id: id(row.id), eventId: uuid(row.eventId), operationScope: text(row.operationScope), requestId: text(row.requestId),
    actorUserId: row.actorUserId === null ? null : id(row.actorUserId), targetType: text(row.targetType), targetId: row.targetId === null ? null : id(row.targetId),
    operation: text(row.operation), status: text(row.status), createdAt, attemptedUsername: row.attemptedUsername === null ? null : text(row.attemptedUsername) };
}
function scalar(value: unknown): AuditScalar {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
  if (typeof value === 'number' && Number.isSafeInteger(value)) return value;
  if (typeof value === 'bigint' && value >= -maxLong - 1n && value <= maxLong) return value;
  throw broken();
}
function changeValue(field: string, value: unknown): AuditValue {
  if (!Array.isArray(value)) return scalar(value);
  if (!['tagIds', 'changedFields'].includes(field) || value.length > (field === 'tagIds' ? 20 : 4)) throw broken();
  const items = value.map(item => {
    if (field === 'changedFields') {
      if (typeof item !== 'string' || !['title', 'summary', 'categoryId', 'tagIds'].includes(item)) throw broken();
      return item;
    }
    if (typeof item !== 'bigint' && !(typeof item === 'number' && Number.isSafeInteger(item))) throw broken();
    try { return positiveId(String(item)); } catch { throw broken(); }
  });
  if (new Set(items).size !== items.length) throw broken();
  return items;
}
/** 403 先交付页面清理，身份核对由页面在清理后触发；保持其他 Session 请求的默认行为。 */
async function auditJson(session: Session, path: string, signal: AbortSignal): Promise<unknown> {
  const epoch = session.getSnapshot().epoch;
  const stale = () => signal.aborted || session.getSnapshot().epoch !== epoch;
  const response = await session.send(path, { signal }, false, false);
  if (stale()) { void response.body?.cancel().catch(() => {}); throw new DOMException('请求已停止', 'AbortError'); }
  if (response.status === 403) { void response.body?.cancel().catch(() => {}); throw new ApiError(403); }
  const value = await readJson<unknown>(response);
  if (stale()) throw new DOMException('请求已停止', 'AbortError');
  return value;
}
export async function auditPage(session: Session, filters: AuditFilters, cursor: string | null, signal: AbortSignal): Promise<AuditPage> {
  const path = auditPath(filters, cursor), row = object(await auditJson(session, path, signal));
  const window = { from: text(row.from), to: text(row.to) };
  try { checkAuditWindow(window); } catch { throw broken(); }
  if (!Array.isArray(row.items) || row.items.length > filters.limit || typeof row.hasMore !== 'boolean'
    || (row.hasMore ? !validAuditCursor(row.nextCursor) || row.items.length === 0 : row.nextCursor !== null)) throw broken();
  const items = row.items.map(metadata);
  if (new Set(items.map(item => item.eventId)).size !== items.length) throw broken();
  if (items.some(item => auditInstant(item.createdAt) < auditInstant(window.from) || auditInstant(item.createdAt) >= auditInstant(window.to))) throw broken();
  return { ...window, items, hasMore: row.hasMore, nextCursor: row.nextCursor as string | null };
}
export async function auditDetail(session: Session, eventId: string, signal: AbortSignal): Promise<AuditDetail> {
  if (!uuidPattern.test(eventId)) throw invalid();
  const row = object(await auditJson(session, `${base}/${eventId}`, signal)), meta = metadata(row.metadata);
  if (meta.eventId !== eventId.toLowerCase() || typeof row.summaryAvailable !== 'boolean' || !Array.isArray(row.changes)) throw broken();
  const changes = row.summaryAvailable ? row.changes.map(value => {
    const change = object(value), field = text(change.field); return { field, before: changeValue(field, change.before), after: changeValue(field, change.after) };
  }) : [];
  return { metadata: meta, changes, summaryAvailable: row.summaryAvailable };
}
export function auditValue(value: AuditValue, field?: string): string {
  if (Array.isArray(value)) return value.length ? value.join('、') : '空集合';
  return value === null ? field === 'categoryId' ? '未分类' : '空值' : value === '' ? '空字符串' : String(value);
}

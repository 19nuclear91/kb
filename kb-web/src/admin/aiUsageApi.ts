import { ApiError, positiveId, readJson } from '../api';
import type { Session } from '../session';
import { auditInstant, checkAuditWindow } from './auditApi';

export type AiKind = 'usage' | 'audits';
export type GroupBy = 'day' | 'department' | 'user';
export interface AiFilters { limit: number; groupBy: GroupBy; userId?: string; departmentId?: string; mode?: string; generationStatus?: string;
  usageStatus?: string; settlementStatus?: string; callId?: string; eventType?: string; from?: string; to?: string }
export const aiDefaults = (): AiFilters => ({ limit: 20, groupBy: 'day' });
export const aiEnums = {
  mode: ['SINGLE', 'MULTI'], generationStatus: ['RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'REJECTED'],
  usageStatus: ['KNOWN', 'UNKNOWN', 'NOT_INVOKED'], settlementStatus: ['NOT_REQUIRED', 'RESERVED', 'PENDING', 'SETTLED', 'REVIEW'],
  eventType: ['REGISTERED', 'ACQUIRE_INTENDED', 'RESERVED', 'DISPATCHED', 'FINALIZED', 'SETTLED', 'REVIEW', 'QUOTA_REJECTED'],
} as const;
export const metricLabels = {
  callCount: '调用数', modelDispatchCount: '模型调度意图数', knownCount: '已知用量样本数', unknownCount: '未知用量数', notInvokedCount: '未调用模型数',
  promptTokens: '已知输入 token', completionTokens: '已知输出 token', plannedChargedTokens: '计划扣除额度', settledChargedTokens: '已确认结算额度',
  runningCount: '执行中', completedCount: '生成完成', failedCount: '生成失败', cancelledCount: '已取消', interruptedCount: '已中断', rejectedCount: '配额拒绝',
  reservedCount: '预留中', pendingCount: '待结算', reviewCount: '待核查', notRequiredCount: '无需结算', settledCount: '结算完成',
} as const;
export type Metrics = Record<keyof typeof metricLabels, string | null>;
export interface AiMetadata { from: string; to: string; asOf: string; dayTimeZone: 'UTC'; collectionEnabled: boolean; collectionStatus: 'ENABLED' | 'DISABLED'; retentionDays: string;
  coverageStartedAt: string | null; coverageIntervals: { from: string; to: string }[]; coverageIntervalsTruncated: boolean; historyMayBeIncomplete: boolean }
export interface AiOverview extends AiMetadata { metrics: Metrics }
export interface AiGroup { key: string; metrics: Metrics; asOf: string }
export interface AiEvent { id: string; eventId: string; callId: string; eventType: string; occurredAt: string; recordedAt: string; snapshotAvailable: boolean; snapshot: Record<string, string | null> }
export interface AiPage extends AiMetadata { items: (AiGroup | AiEvent)[]; hasMore: boolean; nextCursor: string | null; groupBy?: GroupBy }
const invalid = () => new ApiError(400), broken = () => new ApiError(503);
const uuidPattern = /^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i;
const cursorValid = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9_-]{1,4096}$/.test(value);
function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw broken(); return value as Record<string, unknown>; }
function decimal(value: unknown): string { if (typeof value !== 'string' || !/^(0|[1-9]\d{0,127})$/.test(value)) throw broken(); return value; }
function text(value: unknown): string { if (typeof value !== 'string' || value.length > 128) throw broken(); return value; }
function uuid(value: unknown): string { const result = text(value); if (!uuidPattern.test(result)) throw broken(); return result.toLowerCase(); }
function instant(value: unknown): string { const result = text(value); try { auditInstant(result); } catch { throw broken(); } return result; }
function boolean(value: unknown): boolean { if (typeof value !== 'boolean') throw broken(); return value; }
function enumeration(value: unknown, allowed: readonly string[]): string { const result = text(value); if (!allowed.includes(result)) throw broken(); return result; }
function metrics(value: unknown): Metrics {
  const row = object(value), result = {} as Metrics;
  for (const key of Object.keys(metricLabels) as (keyof Metrics)[]) {
    result[key] = row[key] === null && ['promptTokens', 'completionTokens'].includes(key) ? null : decimal(row[key]);
  }
  return result;
}
function metadata(value: Record<string, unknown>): AiMetadata {
  const from = instant(value.from), to = instant(value.to); try { checkAuditWindow({ from, to }); } catch { throw broken(); }
  if (value.dayTimeZone !== 'UTC' || !Array.isArray(value.coverageIntervals) || value.coverageIntervals.length > 100) throw broken();
  const collectionStatus = enumeration(value.collectionStatus, ['ENABLED', 'DISABLED']) as 'ENABLED' | 'DISABLED';
  if (value.collectionEnabled !== (collectionStatus === 'ENABLED')) throw broken();
  return { from, to, asOf: instant(value.asOf), dayTimeZone: 'UTC', collectionEnabled: boolean(value.collectionEnabled), collectionStatus, retentionDays: decimal(value.retentionDays),
    coverageStartedAt: value.coverageStartedAt === null ? null : instant(value.coverageStartedAt),
    coverageIntervals: value.coverageIntervals.map(item => { const interval = object(item); return { from: instant(interval.from), to: instant(interval.to) }; }),
    coverageIntervalsTruncated: boolean(value.coverageIntervalsTruncated), historyMayBeIncomplete: boolean(value.historyMayBeIncomplete) };
}
export function aiPath(kind: 'usage' | 'groups' | 'audits', filters: AiFilters, cursor?: string | null): string {
  if (!Number.isInteger(filters.limit) || filters.limit < 1 || filters.limit > 100 || !['day', 'department', 'user'].includes(filters.groupBy)) throw invalid();
  const query = new URLSearchParams();
  for (const key of ['userId', 'departmentId'] as const) if (filters[key]) {
    if (typeof filters[key] !== 'string') throw invalid(); query.set(key, positiveId(filters[key]));
  }
  for (const key of Object.keys(aiEnums) as (keyof typeof aiEnums)[]) if (filters[key]) {
    if (key === 'eventType' && kind !== 'audits') throw invalid();
    if (!(aiEnums[key] as readonly string[]).includes(filters[key]!)) throw invalid(); query.set(key, filters[key]!);
  }
  if (filters.callId) { if (kind !== 'audits' || !uuidPattern.test(filters.callId)) throw invalid(); query.set('callId', filters.callId.toLowerCase()); }
  if (filters.from || filters.to) {
    if (!filters.from || !filters.to) throw invalid(); checkAuditWindow({ from: filters.from, to: filters.to }); query.set('from', filters.from); query.set('to', filters.to);
  }
  if (kind !== 'usage') query.set('limit', String(filters.limit));
  if (kind === 'groups') query.set('groupBy', filters.groupBy);
  if (cursor != null) { if (kind === 'usage' || !cursorValid(cursor)) throw invalid(); query.set('cursor', cursor); }
  return `/api/admin/ai/${kind === 'groups' ? 'usage/groups' : kind}${query.size ? `?${query}` : ''}`;
}
async function json(session: Session, path: string, signal: AbortSignal): Promise<unknown> {
  const epoch = session.getSnapshot().epoch;
  const response = await session.send(path, { signal, cache: 'no-store' }, false, false);
  if (signal.aborted || session.getSnapshot().epoch !== epoch) { void response.body?.cancel().catch(() => {}); throw new DOMException('请求已停止', 'AbortError'); }
  if (response.status === 403) { void response.body?.cancel().catch(() => {}); throw new ApiError(403); }
  const result = await readJson<unknown>(response);
  if (signal.aborted || session.getSnapshot().epoch !== epoch) throw new DOMException('请求已停止', 'AbortError');
  return result;
}
export async function aiOverview(session: Session, filters: AiFilters, signal: AbortSignal): Promise<AiOverview> {
  const row = object(await json(session, aiPath('usage', filters), signal)); return { ...metadata(row), metrics: metrics(row.metrics) };
}
const snapshotNumbers = ['userId', 'departmentId', 'promptTokens', 'completionTokens', 'reservedTokens', 'plannedChargedTokens', 'settledChargedTokens', 'elapsedMs'];
const snapshotTimes = ['startedAt', 'finalizedAt', 'settledAt'];
function event(value: unknown): AiEvent {
  const row = object(value), result: AiEvent = { id: decimal(row.id), eventId: uuid(row.eventId), callId: uuid(row.callId), eventType: text(row.eventType),
    occurredAt: instant(row.occurredAt), recordedAt: instant(row.recordedAt), snapshotAvailable: boolean(row.snapshotAvailable), snapshot: {} };
  if (result.snapshotAvailable) {
    const source = object(row.snapshot);
    for (const key of ['callId', 'turnId', ...snapshotNumbers, ...snapshotTimes, 'mode', 'generationStatus', 'resultCode', 'usageStatus', 'settlementStatus', 'settlementPolicy']) {
      const value = source[key];
      result.snapshot[key] = value == null ? null : snapshotNumbers.includes(key) ? decimal(value) : snapshotTimes.includes(key) ? instant(value)
        : key.endsWith('Id') ? uuid(value) : text(value);
    }
    if (result.snapshot.callId !== result.callId || !result.snapshot.userId || !result.snapshot.departmentId || !result.snapshot.mode) throw broken();
  }
  return result;
}
export async function aiPage(session: Session, kind: AiKind, filters: AiFilters, cursor: string | null, signal: AbortSignal): Promise<AiPage> {
  const row = object(await json(session, aiPath(kind === 'usage' ? 'groups' : 'audits', filters, cursor), signal)), meta = metadata(row);
  const hasMore = boolean(row.hasMore);
  if (!Array.isArray(row.items) || row.items.length > filters.limit || (hasMore ? !cursorValid(row.nextCursor) || row.items.length === 0 : row.nextCursor !== null)) throw broken();
  if (kind === 'usage' && row.groupBy !== filters.groupBy) throw broken();
  const items = row.items.map(item => {
    if (kind === 'audits') {
      const value = event(item), time = auditInstant(value.occurredAt);
      if (time < auditInstant(meta.from) || time >= auditInstant(meta.to)) throw broken(); return value;
    }
    const value = object(item), key = text(value.key);
    try {
      if (filters.groupBy === 'day') {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(key)) throw broken();
        const day = auditInstant(`${key}T00:00:00Z`);
        if (day >= auditInstant(meta.to) || day + 86400000000n <= auditInstant(meta.from)) throw broken();
      } else if (positiveId(key) !== key) throw broken();
    } catch { throw broken(); }
    return { key, metrics: metrics(value), asOf: meta.asOf };
  });
  return { ...meta, items, hasMore, nextCursor: row.nextCursor as string | null, ...(kind === 'usage' ? { groupBy: filters.groupBy } : {}) };
}
export async function aiDetail(session: Session, eventId: string, signal: AbortSignal): Promise<AiEvent> {
  if (!uuidPattern.test(eventId)) throw invalid();
  const result = event(await json(session, `/api/admin/ai/audits/${eventId}`, signal));
  if (result.eventId !== eventId.toLowerCase()) throw broken(); return result;
}

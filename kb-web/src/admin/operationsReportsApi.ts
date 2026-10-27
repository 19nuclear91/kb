import { ApiError, readJson } from '../api';
import type { Session } from '../session';
import { auditInstant, checkAuditWindow } from './auditApi';

export interface OperationsFilters { from?: string; to?: string }
export interface OperationsCoverage { status: 'COMPLETE' | 'PARTIAL' | 'NOT_STARTED'; coverageStartedAt: string | null; effectiveFrom: string | null; effectiveTo: string | null }
export interface OperationsReview { approvedCount: string | null; rejectedCount: string | null; durationSampleCount: string | null; durationUnknownCount: string | null;
  decisionElapsedMsTotal: string | null; meanDecisionMs: string | null; coverage: OperationsCoverage }
export interface OperationsComments { rootCommentCount: string | null; replyCount: string | null; coverage: OperationsCoverage }
export interface OperationsDay { day: string; from: string; to: string; partialDay: boolean; review: OperationsReview; comments: OperationsComments }
export const reportFormats = ['pdf', 'txt', 'md', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'UNKNOWN'] as const;
export const reportParsingStates = ['pending', 'parsing', 'indexed', 'failed', 'UNKNOWN'] as const;
export interface OperationsReport { definitionVersion: 'OPERATIONS_V1'; asOf: string; from: string; to: string; dayTimeZone: 'UTC';
  inventory: { documentCount: string; archivedDocumentCount: string; versionCount: string; formats: Record<typeof reportFormats[number], string>; parsingStates: Record<typeof reportParsingStates[number], string> };
  review: OperationsReview & { pendingReviewCount: string }; comments: OperationsComments; days: OperationsDay[] }
const broken = () => new ApiError(503);
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw broken();
  return value as Record<string, unknown>;
}
function text(value: unknown): string { if (typeof value !== 'string') throw broken(); return value; }
function count(value: unknown): string { const result = text(value); if (!/^(0|[1-9]\d*)$/.test(result)) throw broken(); return result; }
function instant(value: unknown): string {
  const result = text(value); try { auditInstant(result); } catch { throw broken(); } return result;
}
function coverage(value: unknown, from: string, to: string): OperationsCoverage {
  const row = object(value), start = row.coverageStartedAt === null ? null : instant(row.coverageStartedAt);
  const status = start === null || auditInstant(start) >= auditInstant(to) ? 'NOT_STARTED' : auditInstant(start) > auditInstant(from) ? 'PARTIAL' : 'COMPLETE';
  const effectiveFrom = status === 'NOT_STARTED' ? null : status === 'PARTIAL' ? start : from, effectiveTo = status === 'NOT_STARTED' ? null : to;
  if (row.status !== status || row.effectiveFrom !== effectiveFrom || row.effectiveTo !== effectiveTo) throw broken();
  return { status, coverageStartedAt: start, effectiveFrom, effectiveTo };
}
function observed(value: unknown, coverage: OperationsCoverage): string | null {
  if (coverage.status === 'NOT_STARTED') { if (value !== null) throw broken(); return null; } return count(value);
}
function review(value: unknown, from: string, to: string): OperationsReview {
  const row = object(value), c = coverage(row.coverage, from, to);
  const result: OperationsReview = { approvedCount: observed(row.approvedCount, c), rejectedCount: observed(row.rejectedCount, c), durationSampleCount: observed(row.durationSampleCount, c),
    durationUnknownCount: observed(row.durationUnknownCount, c), decisionElapsedMsTotal: observed(row.decisionElapsedMsTotal, c), meanDecisionMs: row.meanDecisionMs === null ? null : text(row.meanDecisionMs), coverage: c };
  if (result.durationSampleCount === null || result.durationSampleCount === '0') {
    if (result.meanDecisionMs !== null || (result.durationSampleCount === '0' && result.decisionElapsedMsTotal !== '0')) throw broken();
  } else {
    if (result.meanDecisionMs === null || !/^(0|[1-9]\d*)\.\d{2}$/.test(result.meanDecisionMs)) throw broken();
    const total = BigInt(result.decisionElapsedMsTotal!), samples = BigInt(result.durationSampleCount);
    const hundredths = (total * 100n * 2n + samples) / (samples * 2n);
    if (`${hundredths / 100n}.${String(hundredths % 100n).padStart(2, '0')}` !== result.meanDecisionMs) throw broken();
  }
  if (c.status !== 'NOT_STARTED' && BigInt(result.approvedCount!) + BigInt(result.rejectedCount!) !== BigInt(result.durationSampleCount!) + BigInt(result.durationUnknownCount!)) throw broken();
  return result;
}
function comments(value: unknown, from: string, to: string): OperationsComments {
  const row = object(value), c = coverage(row.coverage, from, to);
  return { rootCommentCount: observed(row.rootCommentCount, c), replyCount: observed(row.replyCount, c), coverage: c };
}
function buckets<K extends string>(value: unknown, keys: readonly K[]): Record<K, string> {
  const row = object(value), result = {} as Record<K, string>;
  for (const key of keys) result[key] = count(row[key]); return result;
}
export function parseOperationsReport(value: unknown): OperationsReport {
  const row = object(value), from = instant(row.from), to = instant(row.to), asOf = instant(row.asOf);
  try { checkAuditWindow({ from, to }); } catch { throw broken(); }
  if (auditInstant(to) > auditInstant(asOf) || row.definitionVersion !== 'OPERATIONS_V1' || row.dayTimeZone !== 'UTC' || !Array.isArray(row.days) || !row.days.length || row.days.length > 32) throw broken();
  const inventory = object(row.inventory), r = object(row.review);
  const stock = { documentCount: count(inventory.documentCount), archivedDocumentCount: count(inventory.archivedDocumentCount), versionCount: count(inventory.versionCount),
    formats: buckets(inventory.formats, reportFormats), parsingStates: buckets(inventory.parsingStates, reportParsingStates) };
  if (BigInt(stock.archivedDocumentCount) > BigInt(stock.documentCount) || [stock.formats, stock.parsingStates].some(distribution =>
    Object.values(distribution).reduce((sum, item) => sum + BigInt(item), 0n) !== BigInt(stock.versionCount))) throw broken();
  const result: OperationsReport = { definitionVersion: 'OPERATIONS_V1', asOf, from, to, dayTimeZone: 'UTC', inventory: stock,
    review: { ...review(r, from, to), pendingReviewCount: count(r.pendingReviewCount) }, comments: comments(row.comments, from, to), days: [] };
  let cursor = auditInstant(from);
  for (const item of row.days) {
    const day = object(item), dayFrom = instant(day.from), dayTo = instant(day.to), label = text(day.day);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(label)) throw broken();
    const midnight = auditInstant(`${label}T00:00:00Z`), end = midnight + 86400000000n < auditInstant(to) ? midnight + 86400000000n : auditInstant(to);
    if (auditInstant(dayFrom) !== cursor || cursor < midnight || cursor >= midnight + 86400000000n || auditInstant(dayTo) !== end
      || typeof day.partialDay !== 'boolean' || day.partialDay !== (cursor !== midnight || end !== midnight + 86400000000n)) throw broken();
    const dr = review(day.review, dayFrom, dayTo), dc = comments(day.comments, dayFrom, dayTo);
    if (dr.coverage.coverageStartedAt !== result.review.coverage.coverageStartedAt || dc.coverage.coverageStartedAt !== result.comments.coverage.coverageStartedAt) throw broken();
    result.days.push({ day: label, from: dayFrom, to: dayTo, partialDay: day.partialDay, review: dr, comments: dc }); cursor = end;
  }
  if (cursor !== auditInstant(to)) throw broken();
  for (const key of ['approvedCount', 'rejectedCount', 'durationSampleCount', 'durationUnknownCount', 'decisionElapsedMsTotal'] as const) {
    if (result.review[key] !== null && result.days.reduce((sum, day) => sum + BigInt(day.review[key] ?? '0'), 0n) !== BigInt(result.review[key])) throw broken();
  }
  for (const key of ['rootCommentCount', 'replyCount'] as const) {
    if (result.comments[key] !== null && result.days.reduce((sum, day) => sum + BigInt(day.comments[key] ?? '0'), 0n) !== BigInt(result.comments[key])) throw broken();
  }
  for (const c of [result.review.coverage, result.comments.coverage]) if (c.coverageStartedAt !== null && auditInstant(c.coverageStartedAt) > auditInstant(asOf)) throw broken();
  return result;
}
export function operationsReportsPath(filters: OperationsFilters): string {
  if (Object.keys(filters).some(key => key !== 'from' && key !== 'to')) throw new ApiError(400);
  if (filters.from === undefined && filters.to === undefined) return '/api/admin/reports/operations';
  if (!filters.from || !filters.to) throw new ApiError(400);
  checkAuditWindow({ from: filters.from, to: filters.to });
  return `/api/admin/reports/operations?${new URLSearchParams({ from: filters.from, to: filters.to })}`;
}
export async function operationsReport(session: Session, filters: OperationsFilters, signal: AbortSignal): Promise<OperationsReport> {
  const epoch = session.getSnapshot().epoch;
  const response = await session.send(operationsReportsPath(filters), { signal, cache: 'no-store' }, false, false);
  if (signal.aborted || session.getSnapshot().epoch !== epoch) { void response.body?.cancel().catch(() => {}); throw new DOMException('请求已停止', 'AbortError'); }
  const result = await readJson<unknown>(response);
  if (signal.aborted || session.getSnapshot().epoch !== epoch) throw new DOMException('请求已停止', 'AbortError');
  return parseOperationsReport(result);
}

import { ApiError } from '../api';
import type { Candidate, Detail, Digest, DigestDetail, Group, Lookup, Page, Preference, Receipt, Subscription, Update } from './types';

export function requireValue(ok: unknown): asserts ok { if (!ok) throw new ApiError(503); }
export function object(value: unknown, keys: string[]): Record<string, unknown> {
  requireValue(value && typeof value === 'object' && !Array.isArray(value));
  requireValue(Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)));
  return value as Record<string, unknown>;
}
export function text(value: unknown): string {
  requireValue(typeof value === 'string' && !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(value));
  return value;
}
export function decimal(value: unknown, minimum = 0): string {
  requireValue(typeof value === 'string' && /^(0|[1-9]\d{0,18})$/.test(value) && BigInt(value) >= BigInt(minimum) && BigInt(value) <= 9223372036854775807n);
  return value;
}
export function uuid(value: unknown): string { requireValue(typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)); return value; }
function bool(value: unknown): boolean { requireValue(typeof value === 'boolean'); return value; }
function time(value: unknown): string { const result = text(value); requireValue(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(result) && Number.isFinite(Date.parse(result))); return result; }
export function week(value: unknown): string {
  const result = text(value), date = new Date(`${result}T00:00:00Z`);
  requireValue(/^\d{4}-\d\d-\d\d$/.test(result) && Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === result && date.getUTCDay() === 1);
  return result;
}
function integer(value: unknown): number { requireValue(typeof value === 'number' && Number.isSafeInteger(value) && value > 0); return value; }
const nullable = <T>(value: unknown, parser: (value: unknown) => T): T | null => value === null ? null : parser(value);
function one<T extends string>(value: unknown, options: readonly T[]): T { requireValue(typeof value === 'string' && options.includes(value as T)); return value as T; }
const kind = (value: unknown) => one(value, ['DOCUMENT', 'SPACE', 'TAG'] as const);
const mode = (value: unknown) => one(value, ['PER_PUBLICATION', 'WEEKLY'] as const);
const state = (value: unknown) => one(value, ['ACTIVE', 'CANCELLED'] as const);
export function cursor(value: unknown): string { const result = text(value); requireValue(/^[A-Za-z0-9_-]{1,2731}$/.test(result)); return result; }
export function page<T>(value: unknown, parse: (value: unknown) => T): Page<T> {
  const v = object(value, ['items', 'hasMore', 'nextCursor', 'asOf']);
  requireValue(Array.isArray(v.items) && v.items.length <= 20);
  const items = v.items.map(parse), hasMore = bool(v.hasMore), nextCursor = nullable(v.nextCursor, cursor);
  requireValue(hasMore ? items.length > 0 && nextCursor !== null : nextCursor === null);
  return { items, hasMore, nextCursor, asOf: time(v.asOf) };
}
export function candidate(value: unknown): Candidate {
  const v = object(value, ['kind', 'targetId', 'label', 'enabled', 'subscriptionId', 'generationId', 'version', 'state']);
  const result = { kind: kind(v.kind), targetId: decimal(v.targetId, 1), label: text(v.label), enabled: bool(v.enabled), subscriptionId: nullable(v.subscriptionId, uuid),
    generationId: nullable(v.generationId, uuid), version: decimal(v.version), state: nullable(v.state, state) };
  requireValue(result.version === '0' ? result.subscriptionId === null && result.generationId === null && result.state === null : result.subscriptionId && result.generationId && result.state);
  return result;
}
export function subscription(value: unknown): Subscription {
  const v = object(value, ['subscriptionId', 'generationId', 'kind', 'state', 'version', 'available', 'targetId', 'label', 'enabled', 'createdAt', 'updatedAt']);
  const available = bool(v.available);
  requireValue(available ? v.targetId !== null && v.label !== null && v.enabled !== null : v.targetId === null && v.label === null && v.enabled === null);
  return { subscriptionId: uuid(v.subscriptionId), generationId: uuid(v.generationId), kind: kind(v.kind), state: state(v.state), version: decimal(v.version, 1), available,
    targetId: nullable(v.targetId, value => decimal(value, 1)), label: nullable(v.label, text), enabled: nullable(v.enabled, bool), createdAt: time(v.createdAt), updatedAt: time(v.updatedAt) };
}
export function preference(value: unknown): Preference {
  const v = object(value, ['mode', 'version', 'effectiveAt', 'timeZone', 'weeklySchedule']); requireValue(v.timeZone === 'Asia/Shanghai');
  return { mode: mode(v.mode), version: decimal(v.version), effectiveAt: nullable(v.effectiveAt, time), timeZone: v.timeZone, weeklySchedule: text(v.weeklySchedule) };
}
export function receipt(value: unknown): Receipt {
  const v = object(value, ['requestId', 'operation', 'subscriptionId', 'generationId', 'version', 'state', 'mode', 'effectiveAt', 'replayed']);
  const operation = one(v.operation, ['SUBSCRIBE', 'CANCEL', 'PREFERENCE'] as const);
  requireValue(operation === 'PREFERENCE' ? v.subscriptionId === null && v.generationId === null && v.state === null && v.mode !== null
    : v.subscriptionId !== null && v.generationId !== null && v.mode === null && v.state === (operation === 'SUBSCRIBE' ? 'ACTIVE' : 'CANCELLED'));
  return { requestId: text(v.requestId), operation, subscriptionId: nullable(v.subscriptionId, uuid), generationId: nullable(v.generationId, uuid), version: decimal(v.version, 1),
    state: nullable(v.state, state), mode: nullable(v.mode, mode), effectiveAt: time(v.effectiveAt), replayed: bool(v.replayed) };
}
export function lookup(value: unknown): Lookup {
  const v = object(value, ['requestId', 'status', 'receipt']), status = one(v.status, ['CONFIRMED', 'UNKNOWN'] as const);
  requireValue(status === 'UNKNOWN' ? v.receipt === null : v.receipt !== null);
  return { requestId: text(v.requestId), status, receipt: nullable(v.receipt, receipt) };
}
export function update(value: unknown): Update {
  const v = object(value, ['updateId', 'documentId', 'versionId', 'versionNo', 'title', 'publishedAt', 'weekStart', 'mode']);
  return { updateId: uuid(v.updateId), documentId: decimal(v.documentId, 1), versionId: decimal(v.versionId, 1), versionNo: integer(v.versionNo), title: text(v.title), publishedAt: time(v.publishedAt), weekStart: week(v.weekStart), mode: mode(v.mode) };
}
export function detail(value: unknown): Detail {
  const v = object(value, ['update', 'hasNote', 'text', 'noteVersion', 'corrected', 'createdAfterVisible', 'baseVersionId', 'baseVersionNo', 'comparisonStatus']);
  const comparisonStatus = one(v.comparisonStatus, ['BASE_AVAILABLE', 'NO_BASE', 'BASE_UNAVAILABLE'] as const), hasNote = bool(v.hasNote);
  requireValue(comparisonStatus === 'BASE_AVAILABLE' ? v.baseVersionId !== null && v.baseVersionNo !== null : v.baseVersionId === null && v.baseVersionNo === null);
  requireValue(hasNote ? typeof v.text === 'string' : v.text === null);
  return { update: update(v.update), hasNote, text: nullable(v.text, text), noteVersion: decimal(v.noteVersion), corrected: bool(v.corrected), createdAfterVisible: bool(v.createdAfterVisible),
    baseVersionId: nullable(v.baseVersionId, value => decimal(value, 1)), baseVersionNo: nullable(v.baseVersionNo, integer), comparisonStatus };
}
export function digest(value: unknown): Digest {
  const v = object(value, ['digestId', 'weekStart', 'weekEnd', 'generatedAt']), start = week(v.weekStart), end = week(v.weekEnd);
  requireValue(Date.parse(end) - Date.parse(start) === 7 * 86400000);
  return { digestId: uuid(v.digestId), weekStart: start, weekEnd: end, generatedAt: time(v.generatedAt) };
}
function group(value: unknown): Group {
  const v = object(value, ['documentId', 'title', 'publicationCount', 'latestPublishedAt']);
  return { documentId: decimal(v.documentId, 1), title: text(v.title), publicationCount: decimal(v.publicationCount, 1), latestPublishedAt: time(v.latestPublishedAt) };
}
export function digestDetail(value: unknown): DigestDetail { const v = object(value, ['digest', 'groups']); return { digest: digest(v.digest), groups: page(v.groups, group) }; }

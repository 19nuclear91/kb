import { parse } from 'lossless-json';
import { ApiError } from '../api';
import type { Availability, Capabilities, CommandResult, Dates, Detail, History, Item, Management, Page, Person, Receipt, Review, Revision, Search, Source, SourceOption, Stewardship, Workspace } from './types';

export class CardError extends ApiError {
  constructor(status: number) {
    super(status);
    this.message = ({ 0: '写入结果尚未确认，请主动核对原请求。', 400: '标准答案输入不符合要求。', 401: '登录状态已失效。',
      403: '当前标准答案操作资格不足。', 404: '所选标准答案不存在或当前不可访问，请主动重新查询。',
      409: '内容、依据、人员或审核状态已变化，请重新核对。', 413: '标准答案请求超过大小限制。',
      429: '标准答案搜索繁忙，请稍后重试。', 503: '标准答案功能暂不可用。' } as Record<number, string>)[status] ?? '标准答案请求未能完成。';
  }
}
export function requireValue(condition: unknown): asserts condition { if (!condition) throw new CardError(503); }
export function object(value: unknown, required: readonly string[], optional: readonly string[] = []): Record<string, unknown> {
  requireValue(value !== null && typeof value === 'object' && !Array.isArray(value));
  const row = value as Record<string, unknown>;
  requireValue(required.every(key => Object.hasOwn(row, key)) && Object.keys(row).every(key => required.includes(key) || optional.includes(key)));
  return row;
}
export function id(value: unknown, zero = false): string {
  requireValue(typeof value === 'string' && (zero ? /^(0|[1-9]\d{0,18})$/ : /^[1-9]\d{0,18}$/).test(value) && BigInt(value) <= 9223372036854775807n);
  return value;
}
function nullable<T>(value: unknown, decoder: (value: unknown) => T): T | null { return value === null ? null : decoder(value); }
export function text(value: unknown, max = 16000): string {
  requireValue(typeof value === 'string' && [...value].length <= max && !value.includes('\0')
    && [...value].every(character => { const code = character.codePointAt(0)!; return code < 0xd800 || code > 0xdfff; }));
  return value;
}
function bool(value: unknown): boolean { requireValue(typeof value === 'boolean'); return value; }
function enumeration<T extends string>(value: unknown, allowed: readonly T[]): T { requireValue(typeof value === 'string' && allowed.includes(value as T)); return value as T; }
export function date(value: unknown): string {
  requireValue(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && value >= '1900-01-01' && value <= '2999-12-31');
  const parsed = new Date(`${value}T00:00:00Z`);
  requireValue(Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value);
  return value;
}
function timestamp(value: unknown): string {
  requireValue(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value) && Number.isFinite(Date.parse(value)));
  return value;
}
function array<T>(value: unknown, limit: number, decoder: (item: unknown) => T): T[] {
  requireValue(Array.isArray(value) && value.length <= limit); return value.map(item => decoder(item));
}
const stewards = ['UNASSIGNED', 'NEEDS_REASSIGN', 'DUE_SOON', 'OVERDUE', 'OK'] as const;
export function dates(value: unknown): Dates {
  const row = object(value, ['effectiveFrom', 'untilKind', 'effectiveUntil', 'state']);
  const result: Dates = { effectiveFrom: nullable(row.effectiveFrom, date), untilKind: enumeration(row.untilKind, ['UNDECLARED', 'OPEN_ENDED', 'DATE']),
    effectiveUntil: nullable(row.effectiveUntil, date), state: enumeration(row.state, ['UNDECLARED', 'NOT_YET_EFFECTIVE', 'EXPIRED', 'EXPIRING_SOON', 'EFFECTIVE']) };
  requireValue((result.untilKind === 'DATE') === (result.effectiveUntil !== null) && (!result.effectiveFrom || !result.effectiveUntil || result.effectiveFrom <= result.effectiveUntil));
  return result;
}
export function person(value: unknown): Person {
  const row = object(value, ['userId', 'displayName']); return { userId: id(row.userId), displayName: text(row.displayName, 256) };
}
function stewardship(value: unknown): Stewardship {
  const row = object(value, ['state', 'responsible', 'cycleMonths', 'firstAssignedOn', 'lastConfirmedOn', 'dueDate', 'version']);
  requireValue(row.cycleMonths === null || typeof row.cycleMonths === 'number' && [3, 6, 12, 24].includes(row.cycleMonths));
  const result = { state: enumeration(row.state, stewards), responsible: nullable(row.responsible, person), cycleMonths: row.cycleMonths as number | null,
    firstAssignedOn: nullable(row.firstAssignedOn, date), lastConfirmedOn: nullable(row.lastConfirmedOn, date), dueDate: nullable(row.dueDate, date), version: id(row.version, true) };
  requireValue(result.state === 'UNASSIGNED' ? result.cycleMonths === null && result.firstAssignedOn === null && result.dueDate === null
    : result.cycleMonths !== null && result.firstAssignedOn !== null && result.dueDate !== null);
  return result;
}
function source(value: unknown): Source {
  const row = object(value, ['documentId', 'versionId', 'versionNo', 'title', 'applicabilityVersion', 'boundDates', 'currentDates', 'currentApplicabilityVersion', 'currentVersionId', 'previewPath']);
  const documentId = id(row.documentId), versionId = id(row.versionId);
  requireValue(row.previewPath === `/api/documents/${documentId}/versions/${versionId}/preview`);
  return { documentId, versionId, versionNo: id(row.versionNo), title: text(row.title, 2000), applicabilityVersion: id(row.applicabilityVersion, true),
    boundDates: dates(row.boundDates), currentDates: dates(row.currentDates), currentApplicabilityVersion: id(row.currentApplicabilityVersion, true),
    currentVersionId: nullable(row.currentVersionId, id), previewPath: row.previewPath };
}
export function sourceOption(value: unknown): SourceOption {
  const row = object(value, ['documentId', 'versionId', 'versionNo', 'title', 'applicabilityVersion', 'dates']);
  return { documentId: id(row.documentId), versionId: id(row.versionId), versionNo: id(row.versionNo), title: text(row.title, 2000),
    applicabilityVersion: id(row.applicabilityVersion, true), dates: dates(row.dates) };
}
export function revision(value: unknown): Revision {
  const row = object(value, ['cardId', 'revisionId', 'revisionNo', 'branchId', 'question', 'aliases', 'answer', 'applicabilityText', 'dates', 'sources', 'availability']);
  const sources = array(row.sources, 10, source);
  requireValue(new Set(sources.map(item => item.documentId)).size === sources.length);
  return { cardId: id(row.cardId), revisionId: id(row.revisionId), revisionNo: id(row.revisionNo), branchId: nullable(row.branchId, id),
    question: text(row.question, 256), aliases: array(row.aliases, 20, item => text(item, 256)), answer: text(row.answer), applicabilityText: text(row.applicabilityText, 2000),
    dates: dates(row.dates), sources, availability: enumeration<Availability>(row.availability, ['AVAILABLE', 'SOURCE_CHANGED', 'NOT_YET_EFFECTIVE', 'EXPIRED', 'DRAFT', 'HISTORICAL', 'UNAVAILABLE']) };
}
export function item(value: unknown): Item {
  const row = object(value, ['cardId', 'revisionId', 'revisionNo', 'question', 'answerExcerpt', 'truncated', 'applicabilityText', 'dates', 'stewardshipState', 'detailPath']);
  const cardId = id(row.cardId), revisionId = id(row.revisionId);
  requireValue(row.detailPath === `/answer-cards/${cardId}/revisions/${revisionId}`);
  return { cardId, revisionId, revisionNo: id(row.revisionNo), question: text(row.question, 256), answerExcerpt: text(row.answerExcerpt, 400),
    truncated: bool(row.truncated), applicabilityText: text(row.applicabilityText, 2000), dates: dates(row.dates),
    stewardshipState: enumeration(row.stewardshipState, stewards), detailPath: row.detailPath };
}
export function page<T>(value: unknown, decoder: (value: unknown) => T, key: (item: T) => string, limit = 100): Page<T> {
  const row = object(value, ['items', 'hasMore', 'nextCursor', 'asOfDate']);
  const items = array(row.items, limit, decoder), hasMore = bool(row.hasMore);
  requireValue(new Set(items.map(key)).size === items.length && (hasMore
    ? typeof row.nextCursor === 'string' && /^[A-Za-z0-9_-]{1,4096}$/.test(row.nextCursor) && items.length > 0 : row.nextCursor === null));
  return { items, hasMore, nextCursor: row.nextCursor as string | null, asOfDate: date(row.asOfDate) };
}
export function search(value: unknown, query: string, spaceId: string | null, limit: number): Search {
  const row = object(value, ['items', 'asOfDate', 'evaluatedAt', 'appliedQuery', 'appliedSpaceId']);
  requireValue(row.appliedQuery === query && row.appliedSpaceId === spaceId);
  const items = array(row.items, limit, item);
  requireValue(new Set(items.map(value => value.cardId)).size === items.length);
  return { items, asOfDate: date(row.asOfDate), evaluatedAt: timestamp(row.evaluatedAt), appliedQuery: query, appliedSpaceId: spaceId };
}
export function detail(value: unknown, cardId: string, revisionId: string): Detail {
  const row = object(value, ['revision', 'stewardship', 'asOfDate']), content = revision(row.revision);
  requireValue(content.cardId === cardId && content.revisionId === revisionId && content.branchId === null && content.availability === 'AVAILABLE' && content.sources.length > 0);
  return { revision: content, stewardship: stewardship(row.stewardship), asOfDate: date(row.asOfDate) };
}
function review(value: unknown): Review {
  const row = object(value, ['reviewId', 'revisionId', 'roundNo', 'state', 'version', 'submittedBy', 'submittedAt', 'decidedAt', 'reason']);
  return { reviewId: id(row.reviewId), revisionId: id(row.revisionId), roundNo: id(row.roundNo), state: enumeration(row.state, ['IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN']),
    version: id(row.version, true), submittedBy: id(row.submittedBy), submittedAt: timestamp(row.submittedAt), decidedAt: nullable(row.decidedAt, timestamp), reason: nullable(row.reason, value => text(value, 2000)) };
}
export function workspace(value: unknown, expected?: string): Workspace {
  const row = object(value, ['cardId', 'spaceId', 'cardVersion', 'published', 'draft', 'review', 'stewardship', 'capabilities', 'asOfDate']);
  const cardId = id(row.cardId), published = nullable(row.published, revision), draft = nullable(row.draft, revision), round = nullable(row.review, review);
  requireValue((!expected || cardId === expected) && (published !== null || draft !== null) && (!published || published.cardId === cardId)
    && (!draft || draft.cardId === cardId) && (!round || draft?.revisionId === round.revisionId));
  const caps = object(row.capabilities, ['canEdit', 'canSubmit', 'canReview', 'canWithdraw', 'canConfirm']);
  Object.values(caps).forEach(bool);
  requireValue(!round || !caps.canEdit && !caps.canSubmit && !caps.canConfirm);
  return { cardId, spaceId: id(row.spaceId), cardVersion: id(row.cardVersion, true), published, draft, review: round, stewardship: stewardship(row.stewardship),
    capabilities: caps as unknown as Capabilities, asOfDate: date(row.asOfDate) };
}
export function management(value: unknown, expected?: string): Management {
  const row = object(value, ['cardId', 'spaceId', 'cardVersion', 'workflowState', 'spaceArchived', 'stewardship', 'asOfDate']);
  const cardId = id(row.cardId); requireValue(!expected || cardId === expected);
  return { cardId, spaceId: id(row.spaceId), cardVersion: id(row.cardVersion, true), workflowState: enumeration(row.workflowState, ['DRAFT', 'IN_REVIEW', 'PUBLISHED']),
    spaceArchived: bool(row.spaceArchived), stewardship: stewardship(row.stewardship), asOfDate: date(row.asOfDate) };
}
export function history(value: unknown): History {
  const row = object(value, ['revision', 'review']);
  const content = nullable(row.revision, revision), round = nullable(row.review, review);
  requireValue(content || round);
  requireValue(!content || !round || content.revisionId === round.revisionId);
  return { revision: content, review: round };
}
export function receipt(value: unknown): Receipt {
  const row = object(value, ['cardId', 'cardVersion', 'branchId', 'revisionId', 'reviewId', 'reviewVersion', 'stewardshipVersion', 'state', 'committedAt', 'replayed']);
  return { cardId: id(row.cardId), cardVersion: id(row.cardVersion, true), branchId: nullable(row.branchId, id), revisionId: nullable(row.revisionId, id),
    reviewId: nullable(row.reviewId, id), reviewVersion: nullable(row.reviewVersion, value => id(value, true)), stewardshipVersion: nullable(row.stewardshipVersion, value => id(value, true)),
    state: enumeration(row.state, ['DRAFT', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'ASSIGNED', 'CONFIRMED']), committedAt: timestamp(row.committedAt), replayed: bool(row.replayed) };
}
export function commandResult(value: unknown): CommandResult {
  const row = object(value, ['status', 'receipt']), status = enumeration(row.status, ['COMMITTED', 'NOT_FOUND']);
  const result = nullable(row.receipt, receipt); requireValue((status === 'COMMITTED') === (result !== null));
  return { status, receipt: result };
}
export function envelope(raw: string): unknown {
  const stack: Array<{ object: boolean; keyExpected: boolean; keys: Set<string> }> = [];
  for (const match of raw.matchAll(/"(?:\\[\s\S]|[^"\\])*"|[{}\[\],:]/g)) {
    const token = match[0], current = stack.at(-1);
    if (token === '{' || token === '[') stack.push({ object: token === '{', keyExpected: token === '{', keys: new Set() });
    else if (token === '}' || token === ']') stack.pop();
    else if (token === ':' && current?.object) current.keyExpected = false;
    else if (token === ',' && current?.object) current.keyExpected = true;
    else if (token.startsWith('"') && current?.object && current.keyExpected) {
      const key = JSON.parse(token) as string; requireValue(!current.keys.has(key)); current.keys.add(key);
    }
  }
  const result = object(parse(raw, undefined, token => {
    requireValue(/^(0|[1-9]\d*)$/.test(token) && Number.isSafeInteger(Number(token))); return Number(token);
  }), ['code', 'message', 'data'], ['ok']);
  requireValue(result.code === 200 && result.message === 'ok' && (!Object.hasOwn(result, 'ok') || result.ok === true));
  return result.data;
}

import { parse } from 'lossless-json';
import { ApiError } from '../api';
import { GAP_CURSOR, gapId, gapKeys, gapText, gapUnicode, gapUuid, gapVersion } from './input';
import { gapCategories, gapEvents, gapSources, gapStates, gapTerminal, type GapCapabilities, type GapDetail,
  type GapDocument, type GapEntry, type GapEntryKind, type GapHistoryItem, type GapItem, type GapPage,
  type GapParticipant, type GapReceipt } from './types';
import type { GapIntent } from './intents';

const itemKeys = ['gapId', 'category', 'source', 'state', 'version', 'reporter', 'assignee', 'needsReassignment', 'createdAt', 'updatedAt'];
const capabilityKeys = ['assign', 'note', 'returnToReception', 'submitSolution', 'confirm', 'rejectSolution', 'withdraw', 'close'] as const;
const entryKinds = ['NOTE', 'RETURN', 'SOLUTION', 'REJECT_SOLUTION', 'WITHDRAW', 'CLOSE'];
function require(valid: unknown): asserts valid { if (!valid) throw new ApiError(503); }
/** lossless-json 会容忍值相同的重复键；先按 JSON 字符串/容器词元拒绝所有重复键。语法仍由正式解析器校验。 */
function rejectDuplicateKeys(text: string): void {
  const stack: Array<{ object: boolean; keyExpected: boolean; keys: Set<string> }> = [];
  for (const match of text.matchAll(/"(?:\\[\s\S]|[^"\\])*"|[{}\[\],:]/g)) {
    const token = match[0], current = stack.at(-1);
    if (token === '{' || token === '[') stack.push({ object: token === '{', keyExpected: token === '{', keys: new Set() });
    else if (token === '}' || token === ']') stack.pop();
    else if (token === ':' && current?.object) current.keyExpected = false;
    else if (token === ',' && current?.object) current.keyExpected = true;
    else if (token.startsWith('"') && current?.object && current.keyExpected) {
      const key = JSON.parse(token) as string;
      require(!current.keys.has(key)); current.keys.add(key);
    }
  }
}
/** 原始 JSON 整数直接解析成 bigint，拒绝小数/指数/重复键，避免先丢精度再校验。 */
export function gapEnvelope(text: string): unknown {
  rejectDuplicateKeys(text);
  const value = parse(text, undefined, token => {
    if (!/^-?(?:0|[1-9]\d*)$/.test(token)) throw new ApiError(503);
    return BigInt(token);
  });
  // R 的 Bean 布尔派生字段 isOk 不作为业务数据，仅允许与 code 一致的成功值。
  require(gapKeys(value, ['code', 'message', 'data'], ['ok']) && value.code === 200n && typeof value.message === 'string'
    && (!Object.hasOwn(value, 'ok') || value.ok === true));
  return value.data;
}
const integer = (value: unknown) => { require(typeof value === 'bigint'); return gapVersion(value); };
const id = (value: unknown) => { require(typeof value === 'bigint'); return gapId(value); };
function timestamp(value: unknown): string {
  require(typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) && Number.isFinite(Date.parse(value)));
  return value;
}
export function gapParticipant(value: unknown): GapParticipant {
  require(gapKeys(value, ['userId', 'label']) && (value.label === null || typeof value.label === 'string'));
  return { userId: id(value.userId), label: value.label === null ? null : gapUnicode(value.label) };
}
export function gapDocument(value: unknown): GapDocument {
  require(gapKeys(value, ['documentId', 'versionId', 'title']) && typeof value.title === 'string');
  return { documentId: id(value.documentId), versionId: id(value.versionId), title: gapUnicode(value.title) };
}
function entry(value: unknown): GapEntry {
  require(gapKeys(value, ['entryId', 'position', 'kind', 'author', 'text', 'referenceState', 'reference', 'createdAt'])
    && typeof value.kind === 'string' && entryKinds.includes(value.kind));
  const kind = value.kind as GapEntryKind, referenceState = value.referenceState;
  require(['NONE', 'AVAILABLE', 'UNAVAILABLE'].includes(String(referenceState)));
  require(referenceState === 'AVAILABLE' ? value.reference !== null : value.reference === null);
  require(kind === 'SOLUTION' || referenceState === 'NONE');
  return { entryId: gapUuid(value.entryId), position: integer(value.position), kind, author: gapParticipant(value.author),
    text: gapText(value.text, kind === 'SOLUTION' ? 4000 : 2000), referenceState: referenceState as GapEntry['referenceState'],
    reference: referenceState === 'AVAILABLE' ? gapDocument(value.reference) : null, createdAt: timestamp(value.createdAt) };
}
function baseItem(value: Record<string, unknown>): GapItem {
  require(gapStates.includes(value.state as GapItem['state']) && gapCategories.includes(value.category as GapItem['category'])
    && gapSources.includes(value.source as GapItem['source']) && typeof value.needsReassignment === 'boolean');
  const state = value.state as GapItem['state'], assignee = value.assignee === null ? null : gapParticipant(value.assignee);
  require(state !== 'SUBMITTED' || assignee === null);
  require((state !== 'IN_PROGRESS' && state !== 'AWAITING_CONFIRMATION') || assignee !== null);
  require(!value.needsReassignment || (assignee !== null && !gapTerminal(state)));
  return { gapId: id(value.gapId), category: value.category as GapItem['category'], source: value.source as GapItem['source'], state,
    version: integer(value.version), reporter: gapParticipant(value.reporter), assignee, needsReassignment: value.needsReassignment,
    createdAt: timestamp(value.createdAt), updatedAt: timestamp(value.updatedAt) };
}
export function gapItem(value: unknown): GapItem { require(gapKeys(value, itemKeys)); return baseItem(value); }
export function gapDetailValue(value: unknown, expectedId: string): GapDetail {
  require(gapKeys(value, [...itemKeys, 'description', 'consentVersion', 'capabilities', 'currentSolution']) && value.consentVersion === 'GAP_SHARE_V1');
  const item = baseItem(value);
  const rawCapabilities = value.capabilities;
  require(item.gapId === expectedId && gapKeys(rawCapabilities, capabilityKeys) && capabilityKeys.every(key => typeof rawCapabilities[key] === 'boolean'));
  const capabilities = { ...rawCapabilities } as unknown as GapCapabilities;
  require(!gapTerminal(item.state) || capabilityKeys.every(key => !capabilities[key]));
  require(!capabilities.submitSolution || item.state === 'IN_PROGRESS');
  require((!capabilities.confirm && !capabilities.rejectSolution) || item.state === 'AWAITING_CONFIRMATION');
  const solution = value.currentSolution === null ? null : entry(value.currentSolution);
  require((item.state === 'AWAITING_CONFIRMATION') === (solution !== null));
  require(solution === null || (solution.kind === 'SOLUTION' && solution.position <= item.version && solution.author.userId === item.assignee?.userId));
  return { ...item, description: gapText(value.description, 4000), consentVersion: 'GAP_SHARE_V1', capabilities, currentSolution: solution };
}
export function gapHistoryValue(value: unknown): GapHistoryItem {
  require(gapKeys(value, ['eventId', 'kind', 'resultVersion', 'actor', 'assignee', 'occurredAt', 'solutionId', 'entry'])
    && gapEvents.includes(value.kind as GapHistoryItem['kind']));
  const kind = value.kind as GapHistoryItem['kind'], version = integer(value.resultVersion), actor = gapParticipant(value.actor);
  const detail = value.entry === null ? null : entry(value.entry), solutionId = value.solutionId === null ? null : gapUuid(value.solutionId);
  const expectedEntry = ({ NOTE_ADDED: 'NOTE', GAP_RETURNED: 'RETURN', GAP_SOLUTION_SUBMITTED: 'SOLUTION',
    GAP_SOLUTION_REJECTED: 'REJECT_SOLUTION', GAP_WITHDRAWN: 'WITHDRAW', GAP_CLOSED: 'CLOSE' } as Partial<Record<GapHistoryItem['kind'], GapEntryKind>>)[kind];
  require(expectedEntry ? detail?.kind === expectedEntry : detail === null);
  require(detail === null || (detail.position === version && detail.author.userId === actor.userId));
  require(['GAP_SOLUTION_SUBMITTED', 'GAP_SOLUTION_REJECTED', 'GAP_RESOLVED'].includes(kind) ? solutionId !== null : solutionId === null);
  require(kind !== 'GAP_CREATED' || version === 0n);
  require(kind !== 'GAP_SOLUTION_SUBMITTED' || solutionId === detail?.entryId);
  return { eventId: gapUuid(value.eventId), kind, resultVersion: version, actor,
    assignee: value.assignee === null ? null : gapParticipant(value.assignee), occurredAt: timestamp(value.occurredAt), solutionId, entry: detail };
}
export function gapPageValue<T>(value: unknown, limit: number, parseItem: (item: unknown) => T, key: (item: T) => string): GapPage<T> {
  require(gapKeys(value, ['items', 'hasMore', 'nextCursor']) && Array.isArray(value.items) && value.items.length <= limit && typeof value.hasMore === 'boolean');
  require(value.hasMore ? typeof value.nextCursor === 'string' && GAP_CURSOR.test(value.nextCursor) && value.items.length > 0 : value.nextCursor === null);
  const items = value.items.map(parseItem);
  require(new Set(items.map(key)).size === items.length);
  return { items, hasMore: value.hasMore, nextCursor: value.nextCursor as string | null };
}
export function gapReceiptValue(value: unknown, intent: GapIntent, status: number): GapReceipt {
  require(gapKeys(value, ['gapId', 'resultVersion', 'state', 'eventId', 'solutionId', 'replayed']) && typeof value.replayed === 'boolean'
    && gapStates.includes(value.state as GapReceipt['state']));
  const target = id(value.gapId), version = integer(value.resultVersion), state = value.state as GapReceipt['state'];
  require(intent.action === 'create' ? version === 0n && state === 'SUBMITTED' && (value.replayed ? status === 200 : status === 201)
    : status === 200 && target === intent.gapId && version === intent.expectedVersion! + 1n);
  const states = { assign: 'IN_PROGRESS', return: 'SUBMITTED', solution: 'AWAITING_CONFIRMATION', confirm: 'RESOLVED',
    'reject-solution': 'IN_PROGRESS', withdraw: 'WITHDRAWN', close: 'CLOSED' } as const;
  if (intent.action === 'note') require(!gapTerminal(state));
  else if (intent.action !== 'create') require(state === states[intent.action]);
  const solution = value.solutionId === null ? null : gapUuid(value.solutionId);
  require(intent.action === 'solution' ? solution !== null : solution === intent.solutionId);
  return { gapId: target, resultVersion: version, state, eventId: gapUuid(value.eventId), solutionId: solution, replayed: value.replayed };
}

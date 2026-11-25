import { parse, parseNumberAndBigInt } from 'lossless-json';
import { ApiError, encodeJson } from '../api';
import { numericId } from '../admin/api';
import type { Session } from '../session';
import type { Hit } from '../types';
import { searchBody, type SearchFilters } from './api';
import { searchMaxQueryCodepoints } from './limits';

export type SearchMode = 'ASSISTED' | 'ORIGINAL';
export type SelectionKind = 'CORRECTION' | 'DISAMBIGUATION';
export interface Selection { kind: SelectionKind; start: number; end: number; termId: string }
export interface AppliedTerm { start: number; end: number; matchedText: string; termId: string; termVersion: string; canonicalName: string; expressions: string[] }
export interface Meaning { termId: string; termVersion: string; canonicalName: string; description: string }
export interface Suggestion { kind: SelectionKind; start: number; end: number; sourceText: string; options: Meaning[] }
export interface Assistance { mode: SearchMode; originalQuery: string; effectiveQuery: string; dictionaryRevision: string | null; appliedTerms: AppliedTerm[]; suggestions: Suggestion[]; selections: Selection[]; selectionContext: string | null }
export interface AssistedResult { hits: Hit[]; assistance: Assistance }
// maxQueryCodepoints 仅为本次客户端校验快照，不属于发送给服务端的协议字段。
export interface AssistedInput { originalQuery: string; filters: SearchFilters; candidates: number; topK: number; mode: SearchMode; selections: readonly Selection[]; selectionContext: string | null; maxQueryCodepoints?: number }

export class AssistanceProtocolError extends ApiError {
  constructor() { super(503); this.message = '当前服务无法确认辅助搜索支持，请稍后重试。'; }
}
export const assistanceReasons = {
  INVALID_INPUT: 400, ASSISTANCE_LIMIT_EXCEEDED: 400, ASSISTANCE_STALE: 409, ASSISTANCE_UNAVAILABLE: 503,
  SEARCH_UNAVAILABLE: 503, USER_LIMIT: 429, CAPACITY: 503, UNAUTHORIZED: 401, FORBIDDEN: 403,
  PAYLOAD_TOO_LARGE: 413, INTERNAL_ERROR: 500,
} as const;
export class AssistanceError extends ApiError {
  constructor(status: number, public readonly reason?: keyof typeof assistanceReasons) { super(status); }
}
const broken = () => new AssistanceProtocolError();
function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw broken();
  return value as Record<string, unknown>;
}
function text(value: unknown): string { if (typeof value !== 'string') throw broken(); return value; }
function integer(value: unknown, min = 0, max = 2147483647): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) throw broken();
  return value;
}
function exact(value: unknown, zero = false): string {
  if (typeof value !== 'string' || !(zero ? /^(0|[1-9]\d{0,18})$/ : /^[1-9]\d{0,18}$/).test(value) || BigInt(value) > 9223372036854775807n) throw broken();
  return value;
}
function termText(value: unknown, maximum: number): string {
  const result = text(value);
  if (!result || result !== result.normalize('NFC') || Array.from(result).length > maximum || /[\p{Cc}\p{Cs}]/u.test(result)) throw broken();
  return result;
}
function array(value: unknown, maximum: number): unknown[] { if (!Array.isArray(value) || value.length > maximum) throw broken(); return value; }
export const expressionKey = (value: string) => value.normalize('NFC').replace(/[A-Z]/g, c => c.toLowerCase());
function selection(value: unknown, length: number): Selection {
  const row = object(value), kind = row.kind;
  if (kind !== 'CORRECTION' && kind !== 'DISAMBIGUATION') throw broken();
  const start = integer(row.start, 0, length), end = integer(row.end, 1, length);
  if (end <= start) throw broken();
  return { kind, start, end, termId: exact(row.termId) };
}
function meaning(value: unknown): Meaning {
  const row = object(value);
  return { termId: exact(row.termId), termVersion: exact(row.termVersion, true), canonicalName: termText(row.canonicalName, 64), description: termText(row.description, 256) };
}
function hit(value: unknown): Hit {
  const row = object(value);
  const result = { chunkId: exact(row.chunkId), docId: exact(row.docId), versionId: exact(row.versionId),
    versionNo: integer(Number(exact(row.versionNo)), 1), docTitle: text(row.docTitle), content: text(row.content) };
  exact(row.spaceId); integer(row.securityLevel, -32768, 32767); integer(row.chunkIdx);
  if (typeof row.score !== 'number' || !Number.isFinite(row.score)) throw broken();
  const optional: Record<string, unknown> = {};
  for (const field of ['pageNo', 'sourceLineStart', 'sourceLineEnd', 'sourceCharStart', 'sourceCharEnd']) {
    if (row[field] !== undefined && row[field] !== null) optional[field] = integer(row[field]);
  }
  for (const field of ['headingPath', 'structureType', 'sourceLocator']) {
    if (row[field] !== undefined && row[field] !== null) optional[field] = text(row[field]);
  }
  return { ...result, ...optional };
}
export function decodeAssisted(value: unknown, input: AssistedInput): AssistedResult {
  const result = object(value), row = object(result.assistance), points = Array.from(input.originalQuery);
  const maximum = input.maxQueryCodepoints ?? searchMaxQueryCodepoints;
  if (!Number.isSafeInteger(maximum) || maximum < 1 || points.length > maximum) throw broken();
  if (row.mode !== input.mode || row.originalQuery !== input.originalQuery) throw broken();
  const selections = array(row.selections, 8).map(item => selection(item, points.length));
  const expected = [...input.selections].sort((a, b) => a.start - b.start);
  if (encodeJson(selections) !== encodeJson(expected)) throw broken();
  const appliedTerms = array(row.appliedTerms, 8).map(value => {
    const item = object(value), start = integer(item.start, 0, points.length), end = integer(item.end, 1, points.length);
    if (end <= start || item.matchedText !== points.slice(start, end).join('')) throw broken();
    const expressions = array(item.expressions, 32).map(expression => termText(expression, 64));
    if (new Set(expressions.map(expressionKey)).size !== expressions.length) throw broken();
    return { start, end, matchedText: text(item.matchedText), termId: exact(item.termId), termVersion: exact(item.termVersion, true), canonicalName: termText(item.canonicalName, 64), expressions };
  });
  const suggestions = array(row.suggestions, 8).map(value => {
    const item = object(value), start = integer(item.start, 0, points.length), end = integer(item.end, 1, points.length), kind = item.kind;
    if ((kind !== 'CORRECTION' && kind !== 'DISAMBIGUATION') || end <= start || item.sourceText !== points.slice(start, end).join('')) throw broken();
    const options = array(item.options, 8).map(meaning);
    if (kind === 'CORRECTION' ? options.length !== 1 : options.length < 2) throw broken();
    if (options.some((option, index) => index > 0 && BigInt(option.termId) <= BigInt(options[index - 1].termId))) throw broken();
    return { kind, start, end, sourceText: text(item.sourceText), options } as Suggestion;
  });
  const positions = [...appliedTerms, ...suggestions].sort((a, b) => a.start - b.start);
  if (positions.length > 8 || positions.some((position, index) => index > 0 && position.start < positions[index - 1].end)) throw broken();
  let effectiveQuery = '', previous = 0;
  for (const chosen of selections) {
    const term = appliedTerms.find(term => term.start === chosen.start && term.end === chosen.end && term.termId === chosen.termId);
    if (!term || chosen.start < previous) throw broken();
    effectiveQuery += points.slice(previous, chosen.start).join('') + term.canonicalName;
    previous = chosen.end;
  }
  effectiveQuery += points.slice(previous).join('');
  if (row.effectiveQuery !== effectiveQuery) throw broken();
  const groups = new Map<string, AppliedTerm>(), expressions = new Map<string, string>();
  for (const term of appliedTerms) {
    const previous = groups.get(term.termId);
    if (previous && (previous.termVersion !== term.termVersion || previous.canonicalName !== term.canonicalName || encodeJson(previous.expressions) !== encodeJson(term.expressions))) throw broken();
    groups.set(term.termId, term);
    term.expressions.forEach(expression => { const key = expressionKey(expression); if (!expressions.has(key)) expressions.set(key, expression); });
  }
  // 已选择的位置只保留替换后的标准名称，旧片段仍可由其他位置合法补充。
  const selectedStarts = new Set(selections.map(chosen => chosen.start));
  const used = new Set([
    ...appliedTerms.map(term => expressionKey(selectedStarts.has(term.start) ? term.canonicalName : term.matchedText)),
    ...suggestions.map(term => expressionKey(term.sourceText)),
  ]);
  if ([...expressions.keys()].some(key => used.has(key))) throw broken();
  const added = [...expressions.values()].reduce((count, value) => count + Array.from(value).length + 1, 0);
  if (expressions.size > 32 || added > 2048 || Array.from(effectiveQuery).length + added > maximum) throw broken();
  const selectionContext = row.selectionContext === null ? null : text(row.selectionContext);
  if (suggestions.length ? !selectionContext || !selectionContext.trim() || selectionContext.length > 4096 : selectionContext !== null) throw broken();
  const dictionaryRevision = row.dictionaryRevision === null ? null : exact(row.dictionaryRevision, true);
  if (input.mode === 'ORIGINAL' ? dictionaryRevision !== null || positions.length !== 0 || selections.length !== 0 || selectionContext !== null : dictionaryRevision === null) throw broken();
  const hits = array(result.hits, input.topK).map(hit);
  if (new Set(hits.map(hit => `${hit.docId}/${hit.versionId}/${hit.chunkId}`)).size !== hits.length) throw broken();
  return { hits, assistance: { mode: input.mode, originalQuery: input.originalQuery, effectiveQuery, dictionaryRevision, appliedTerms, suggestions, selections, selectionContext } };
}
export function assistedBody(input: AssistedInput) {
  if (input.mode !== 'ASSISTED' && input.mode !== 'ORIGINAL') throw new ApiError(400);
  for (const amount of [input.candidates, input.topK]) { if (!Number.isInteger(amount) || amount < 1 || amount > 200) throw new ApiError(400); }
  if (input.selections.length > 8 || (input.selections.length ? !input.selectionContext || input.selectionContext.length > 4096 : input.selectionContext !== null)
    || (input.mode === 'ORIGINAL' && input.selections.length)) throw new ApiError(400);
  let end = 0;
  const selections = [...input.selections].sort((a, b) => a.start - b.start).map(chosen => {
    selection(chosen, Array.from(input.originalQuery).length);
    if (chosen.start < end) throw new ApiError(400);
    end = chosen.end;
    return { ...chosen, termId: numericId(chosen.termId) };
  });
  return { ...searchBody(input.originalQuery, input.filters), candidates: input.candidates, topK: input.topK, mode: input.mode,
    ...(selections.length ? { selectionContext: input.selectionContext, selections } : {}) };
}
async function boundedJson(response: Response, maximum: number): Promise<unknown> {
  if (!response.body || !response.headers.get('Content-Type')?.includes('application/json')) throw broken();
  const reader = response.body.getReader(), chunks: Uint8Array[] = []; let size = 0;
  try {
    for (;;) {
      const part = await reader.read(); if (part.done) break;
      size += part.value.byteLength;
      if (size > maximum) { await reader.cancel().catch(() => {}); throw broken(); }
      chunks.push(part.value);
    }
    const bytes = new Uint8Array(size); let offset = 0;
    chunks.forEach(chunk => { bytes.set(chunk, offset); offset += chunk.byteLength; });
    return parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes), undefined, number => {
      const value = parseNumberAndBigInt(number);
      return typeof value === 'bigint' && value >= BigInt(Number.MIN_SAFE_INTEGER) && value <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(value) : value;
    });
  } catch { throw broken(); } finally { reader.releaseLock(); }
}
export const assistedApi = {
  async search(session: Session, input: AssistedInput, signal?: AbortSignal): Promise<AssistedResult> {
    const epoch = session.getSnapshot().epoch;
    const current = () => { if (signal?.aborted || session.getSnapshot().epoch !== epoch) throw new DOMException('请求已停止', 'AbortError'); };
    const response = await session.send('/api/search/assisted', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: encodeJson(assistedBody(input)), signal });
    try { current(); } catch (failure) { await response.body?.cancel().catch(() => {}); throw failure; }
    if (!response.ok) {
      if (response.status === 404 || response.status === 405) { await response.body?.cancel().catch(() => {}); throw broken(); }
      let reason: keyof typeof assistanceReasons | undefined;
      try {
        const value = object(await boundedJson(response, 8192));
        if (value.code === 500 && value.data === null && typeof value.reasonCode === 'string'
          && Object.hasOwn(assistanceReasons, value.reasonCode) && assistanceReasons[value.reasonCode as keyof typeof assistanceReasons] === response.status) reason = value.reasonCode as keyof typeof assistanceReasons;
      } catch { /* 未知或损坏错误正文不显示远程 message。 */ }
      current(); throw new AssistanceError(response.status, reason);
    }
    if (response.status !== 200 || response.headers.get('X-Search-Filter-Version') !== '1' || response.headers.get('X-Search-Assistance-Version') !== '1') {
      await response.body?.cancel().catch(() => {}); throw broken();
    }
    const envelope = object(await boundedJson(response, 4194304)); current();
    if (envelope.code !== 200) throw broken();
    return decodeAssisted(envelope.data, input);
  },
};

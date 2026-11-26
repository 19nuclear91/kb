import { isAbort } from '../api';
import type { Session } from '../session';
import * as protocol from './protocol';
import { CardError } from './protocol';
import type { CommandResult, Content, Intent, PageInput, Receipt } from './types';

const base = '/api/answer-cards';
const utf8 = new TextEncoder();
export function cardId(value: unknown): string {
  try { return protocol.id(value); } catch { throw new CardError(400); }
}
export function cardVersion(value: unknown): string {
  try { return protocol.id(value, true); } catch { throw new CardError(400); }
}
export function cardText(value: unknown, minimum: number, maximum: number): string {
  try {
    // 与Java String.strip一致，保留不换行空格和BOM等不属于该规则的字符。
    const result = protocol.text(value, 131072).replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g, '');
    if ([...result].length < minimum || [...result].length > maximum) throw new CardError(400);
    return result;
  } catch { throw new CardError(400); }
}
export function content(input: Content): Content {
  try {
    protocol.object(input, ['question', 'aliases', 'answer', 'applicabilityText', 'dates', 'sources']);
    protocol.object(input.dates, ['effectiveFrom', 'untilKind', 'effectiveUntil']);
    const dates = protocol.dates({ ...input.dates, state: 'UNDECLARED' });
    if (!Array.isArray(input.aliases) || input.aliases.length > 20 || !Array.isArray(input.sources) || input.sources.length > 10) throw new CardError(400);
    const question = cardText(input.question, 1, 256), aliases = input.aliases.map(value => cardText(value, 1, 256));
    const normalized = [question, ...aliases].map(value => value.normalize('NFC').toLowerCase());
    if (new Set(normalized).size !== normalized.length) throw new CardError(400);
    const sources = input.sources.map(value => {
      protocol.object(value, ['documentId', 'versionId', 'applicabilityVersion']);
      return { documentId: cardId(value.documentId), versionId: cardId(value.versionId), applicabilityVersion: cardVersion(value.applicabilityVersion) };
    });
    if (new Set(sources.map(value => value.documentId)).size !== sources.length) throw new CardError(400);
    return { question, aliases, answer: cardText(input.answer, 0, 16000), applicabilityText: cardText(input.applicabilityText, 0, 2000),
      dates: { effectiveFrom: dates.effectiveFrom, untilKind: dates.untilKind, effectiveUntil: dates.effectiveUntil }, sources };
  } catch { throw new CardError(400); }
}
function query(input: PageInput): string {
  const parts = new URLSearchParams();
  for (const [key, value] of Object.entries(input)) {
    if (!['spaceId', 'view', 'revisionId', 'limit', 'cursor'].includes(key)) throw new CardError(400);
    if (value == null) continue;
    if (key.endsWith('Id')) cardId(value);
    if (key === 'limit' && (!Number.isInteger(value) || Number(value) < 1 || Number(value) > 100)) throw new CardError(400);
    if (key === 'cursor' && (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,4096}$/.test(value))) throw new CardError(400);
    parts.set(key, String(value));
  }
  return parts.size ? `?${parts}` : '';
}
function jsonBody(value: unknown, limit = 131072): Pick<RequestInit, 'body' | 'headers'> {
  const body = JSON.stringify(value);
  if (!body || utf8.encode(body).length > limit) throw new CardError(413);
  return { body, headers: { 'Content-Type': 'application/json' } };
}
async function read(response: Response, maximum: number): Promise<unknown> {
  if (response.headers.get('X-Answer-Card-Version') !== '1' || response.headers.get('Cache-Control') !== 'no-store'
      || !response.headers.get('Content-Type')?.includes('application/json') || !response.body) {
    await response.body?.cancel().catch(() => {}); throw new CardError(503);
  }
  const reader = response.body.getReader(), chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const next = await reader.read(); if (next.done) break;
      size += next.value.byteLength;
      if (size > maximum) { await reader.cancel().catch(() => {}); throw new CardError(503); }
      chunks.push(next.value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return protocol.envelope(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
}
async function call<T>(session: Session, path: string, options: RequestInit, decode: (value: unknown) => T, write = false, maximum = 24 * 1024 * 1024): Promise<T> {
  const state = session.getSnapshot();
  if (state.phase !== 'ready') throw new CardError(state.phase === 'anonymous' ? 401 : state.phase === 'password' ? 403 : 503);
  const epoch = state.epoch;
  const stale = () => options.signal?.aborted || session.getSnapshot().epoch !== epoch || session.getSnapshot().phase !== 'ready';
  let response: Response;
  try { response = await session.send(path, { ...options, cache: 'no-store' }); }
  catch (error) { if (isAbort(error)) throw error; throw new CardError(write ? 0 : 503); }
  if (stale()) { await response.body?.cancel().catch(() => {}); throw new DOMException('请求已停止', 'AbortError'); }
  if (!response.ok) { await response.body?.cancel().catch(() => {}); throw new CardError(response.status); }
  try {
    if (response.status !== 200) throw new CardError(503);
    const result = decode(await read(response, maximum));
    if (stale()) throw new DOMException('请求已停止', 'AbortError');
    return result;
  } catch (error) {
    if (stale() || isAbort(error)) throw new DOMException('请求已停止', 'AbortError');
    throw new CardError(write ? 0 : 503);
  }
}
function intentPath(intent: Intent): { path: string; method: string } {
  const id = intent.cardId === null ? null : cardId(intent.cardId), review = intent.reviewId === null ? null : cardId(intent.reviewId);
  if ((intent.operation === 'ANSWER_CARD_CREATE') !== (id === null)) throw new CardError(400);
  if (['ANSWER_CARD_APPROVE', 'ANSWER_CARD_REJECT', 'ANSWER_CARD_WITHDRAW'].includes(intent.operation) !== (review !== null)) throw new CardError(400);
  const paths = { ANSWER_CARD_CREATE: [base, 'POST'], ANSWER_CARD_SAVE: [`${base}/${id}/draft`, 'PUT'], ANSWER_CARD_SUBMIT: [`${base}/${id}/reviews`, 'POST'],
    ANSWER_CARD_APPROVE: [`${base}/${id}/reviews/${review}/approve`, 'POST'], ANSWER_CARD_REJECT: [`${base}/${id}/reviews/${review}/reject`, 'POST'],
    ANSWER_CARD_WITHDRAW: [`${base}/${id}/reviews/${review}/withdraw`, 'POST'], ANSWER_CARD_STEWARDSHIP_UPDATE: [`${base}/${id}/stewardship`, 'PUT'],
    ANSWER_CARD_CONFIRM: [`${base}/${id}/stewardship/confirm`, 'POST'] } as const;
  const target = paths[intent.operation]; if (!target) throw new CardError(400);
  return { path: target[0], method: target[1] };
}
function boundReceipt(value: unknown, intent: Intent): Receipt {
  const result = protocol.receipt(value);
  const expected = { ANSWER_CARD_CREATE: 'DRAFT', ANSWER_CARD_SAVE: 'DRAFT', ANSWER_CARD_SUBMIT: 'IN_REVIEW', ANSWER_CARD_APPROVE: 'APPROVED',
    ANSWER_CARD_REJECT: 'REJECTED', ANSWER_CARD_WITHDRAW: 'WITHDRAWN', ANSWER_CARD_STEWARDSHIP_UPDATE: 'ASSIGNED', ANSWER_CARD_CONFIRM: 'CONFIRMED' }[intent.operation];
  protocol.requireValue(result.state === expected && (!intent.cardId || result.cardId === intent.cardId) && (!intent.reviewId || result.reviewId === intent.reviewId));
  if (intent.operation !== 'ANSWER_CARD_CREATE') protocol.requireValue(BigInt(result.cardVersion) === BigInt(cardVersion(intent.payload.expectedCardVersion)) + 1n);
  if (['ANSWER_CARD_SUBMIT', 'ANSWER_CARD_APPROVE', 'ANSWER_CARD_REJECT', 'ANSWER_CARD_WITHDRAW', 'ANSWER_CARD_CONFIRM'].includes(intent.operation))
    protocol.requireValue(result.revisionId === intent.payload.revisionId);
  return result;
}
export const cardApi = {
  browse: (session: Session, input: PageInput, signal: AbortSignal) => call(session, base + query(input), { signal }, value => protocol.page(value, protocol.item, item => item.cardId, input.limit ?? 20)),
  detail: (session: Session, id: string, revision: string, signal: AbortSignal) => call(session, `${base}/${cardId(id)}/revisions/${cardId(revision)}`, { signal }, value => protocol.detail(value, id, revision)),
  search: (session: Session, input: { query: string; spaceId: string | null; limit?: number }, signal: AbortSignal) => {
    const query = cardText(input.query, 1, 256), spaceId = input.spaceId === null ? null : cardId(input.spaceId), limit = input.limit ?? 10;
    if (!Number.isInteger(limit) || limit < 1 || limit > 20) throw new CardError(400);
    return call(session, `${base}/search`, { method: 'POST', ...jsonBody({ query, spaceId, limit }, 16384), signal }, value => protocol.search(value, query, spaceId, limit), false, 262144);
  },
  workspaces: (session: Session, input: PageInput, signal: AbortSignal) => call(session, `${base}/workspace${query(input)}`, { signal }, value => protocol.page(value, protocol.workspace, item => item.cardId, input.limit ?? 20)),
  workspace: (session: Session, id: string, signal: AbortSignal) => call(session, `${base}/${cardId(id)}/workspace`, { signal }, value => protocol.workspace(value, id)),
  history: (session: Session, id: string, input: PageInput, signal: AbortSignal) => call(session, `${base}/${cardId(id)}/history${query(input)}`, { signal }, value => protocol.page(value, protocol.history, item => item.revision?.revisionId ?? item.review!.reviewId, input.limit ?? 20)),
  management: (session: Session, input: PageInput, signal: AbortSignal) => call(session, `${base}/management${query(input)}`, { signal }, value => protocol.page(value, protocol.management, item => item.cardId, input.limit ?? 20)),
  steward: (session: Session, id: string, signal: AbortSignal) => call(session, `${base}/${cardId(id)}/stewardship`, { signal }, value => protocol.management(value, id)),
  sources: (session: Session, spaceId: string, name: string, cursor: string | null, signal: AbortSignal) => call(session, `${base}/source-options`,
    { method: 'POST', ...jsonBody({ spaceId: cardId(spaceId), name: cardText(name, 0, 128), limit: 20, cursor }), signal }, value => protocol.page(value, protocol.sourceOption, item => item.documentId, 20)),
  candidates: (session: Session, id: string, name: string, cursor: string | null, signal: AbortSignal) => call(session, `${base}/${cardId(id)}/stewardship/candidates`,
    { method: 'POST', ...jsonBody({ name: cardText(name, 0, 64), limit: 20, cursor }), signal }, value => protocol.page(value, protocol.person, item => item.userId, 20)),
  write: (session: Session, intent: Intent, requestId: string, signal: AbortSignal) => {
    const { path, method } = intentPath(intent), body = jsonBody(intent.payload);
    return call(session, path, { method, ...body, headers: { ...body.headers, 'X-Request-Id': requestId }, signal }, value => boundReceipt(value, intent), true, 16384);
  },
  command: (session: Session, intent: Intent, requestId: string, signal: AbortSignal) => {
    intentPath(intent);
    return call(session, `${base}/commands/query`, { method: 'POST', ...jsonBody({ ...intent, requestId }), signal }, value => {
      const result: CommandResult = protocol.commandResult(value);
      if (result.receipt) result.receipt = boundReceipt(result.receipt, intent);
      return result;
    }, false, 16384);
  },
};

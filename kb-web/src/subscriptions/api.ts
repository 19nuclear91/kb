import { ApiError, isAbort } from '../api';
import type { Session } from '../session';
import * as p from './protocol';
import type { Candidate, Kind, Mode, Receipt, Subscription } from './types';

const base = '/api/knowledge-subscriptions';
export interface Intent { readonly operation: Receipt['operation']; readonly path: string; readonly method: 'POST' | 'PUT'; readonly body: Readonly<Record<string, string>>; readonly requestId: string }
const intents = new WeakSet<Intent>();
function intent(operation: Intent['operation'], path: string, method: Intent['method'], body: Record<string, string>): Intent {
  const value = Object.freeze({ operation, path, method, body: Object.freeze(body), requestId: crypto.randomUUID() }); intents.add(value); return value;
}
export function subscribeIntent(target: Candidate): Intent {
  p.requireValue(target.enabled && target.state !== 'ACTIVE');
  return intent('SUBSCRIBE', base, 'POST', { kind: target.kind, targetId: p.decimal(target.targetId, 1), expectedVersion: p.decimal(target.version) });
}
export function cancelIntent(target: Pick<Subscription, 'subscriptionId' | 'generationId' | 'version'>): Intent {
  return intent('CANCEL', `${base}/${p.uuid(target.subscriptionId)}/cancel`, 'PUT', { generationId: p.uuid(target.generationId), expectedVersion: p.decimal(target.version, 1) });
}
export function preferenceIntent(mode: Mode, version: string): Intent {
  p.requireValue(mode === 'WEEKLY' || mode === 'PER_PUBLICATION');
  return intent('PREFERENCE', '/api/knowledge-subscription-preferences', 'PUT', { mode, expectedVersion: p.decimal(version) });
}
export function verifyReceipt(value: Receipt, original: Intent): Receipt {
  p.requireValue(value.requestId === original.requestId && value.operation === original.operation && value.version === (BigInt(original.body.expectedVersion!) + 1n).toString());
  if (original.operation === 'CANCEL') p.requireValue(value.generationId === original.body.generationId && original.path === `${base}/${value.subscriptionId}/cancel`);
  if (original.operation === 'PREFERENCE') p.requireValue(value.mode === original.body.mode);
  return value;
}

/** JSON字段完全解码前拒绝所有重复键，响应只接受有界UTF-8和固定R包装。 */
export function envelope(text: string): unknown {
  const stack: Array<{ object: boolean; keyExpected: boolean; keys: Set<string> }> = [];
  for (const match of text.matchAll(/"(?:\\[\s\S]|[^"\\])*"|[{}\[\],:]/g)) {
    const token = match[0], current = stack.at(-1);
    if (token === '{' || token === '[') { p.requireValue(stack.length < 16); stack.push({ object: token === '{', keyExpected: token === '{', keys: new Set() }); }
    else if (token === '}' || token === ']') stack.pop();
    else if (token === ':' && current?.object) current.keyExpected = false;
    else if (token === ',' && current?.object) current.keyExpected = true;
    else if (token.startsWith('"') && current?.object && current.keyExpected) {
      const key = JSON.parse(token) as string; p.requireValue(!current.keys.has(key)); current.keys.add(key);
    }
  }
  const value: unknown = JSON.parse(text), keys = ['code', 'message', 'data'];
  if (value && typeof value === 'object' && Object.hasOwn(value, 'ok')) keys.push('ok');
  const v = p.object(value, keys); p.requireValue(v.code === 200 && typeof v.message === 'string' && (!keys.includes('ok') || v.ok === true)); return v.data;
}
async function call<T>(session: Session, path: string, options: RequestInit, parse: (value: unknown) => T, write = false): Promise<T> {
  const deadline = new AbortController(), signal = options.signal ? AbortSignal.any([options.signal, deadline.signal]) : deadline.signal;
  let stop!: () => void;
  const stopped = new Promise<never>((_, reject) => {
    stop = () => reject(signal.reason);
    if (signal.aborted) stop(); else signal.addEventListener('abort', stop, { once: true });
  });
  const timer = setTimeout(() => deadline.abort(new DOMException('请求等待已到期', 'TimeoutError')), 15000);
  try {
    return await (signal.aborted ? stopped : Promise.race([request(session, path, { ...options, signal }, parse, write), stopped]));
  } catch (error) {
    if (options.signal?.aborted) throw new DOMException('请求已停止', 'AbortError');
    if (deadline.signal.aborted) throw new ApiError(write ? 0 : 503);
    throw error;
  } finally {
    clearTimeout(timer); signal.removeEventListener('abort', stop); deadline.abort();
  }
}
async function request<T>(session: Session, path: string, options: RequestInit, parse: (value: unknown) => T, write: boolean): Promise<T> {
  p.requireValue(session.getSnapshot().phase === 'ready');
  const epoch = session.getSnapshot().epoch;
  const response = await session.send(path, options);
  const stale = () => options.signal?.aborted || epoch !== session.getSnapshot().epoch || session.getSnapshot().phase !== 'ready';
  if (!response.ok) { await response.body?.cancel().catch(() => {}); throw new ApiError(response.status); }
  try {
    p.requireValue(response.status === 200 && response.headers.get('Content-Type')?.includes('application/json') && response.body);
    const reader = response.body.getReader(), chunks: Uint8Array[] = []; let size = 0;
    try {
      for (;;) {
        const next = await reader.read(); if (next.done) break;
        size += next.value.byteLength;
        if (stale() || size > (write ? 16384 : 4 * 1024 * 1024)) { await reader.cancel().catch(() => {}); throw new ApiError(503); }
        chunks.push(next.value);
      }
    } finally { reader.releaseLock(); }
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    if (stale()) throw new DOMException('请求已停止', 'AbortError');
    return parse(envelope(new TextDecoder('utf-8', { fatal: true }).decode(bytes)));
  } catch (error) {
    if (stale() || isAbort(error)) throw new DOMException('请求已停止', 'AbortError');
    throw new ApiError(write ? 0 : 503);
  }
}
const json = (body: unknown, signal: AbortSignal): RequestInit => ({ method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
function query(cursor: string | null) { return `limit=20${cursor === null ? '' : `&cursor=${encodeURIComponent(p.cursor(cursor))}`}`; }
export const subscriptions = (session: Session, cursor: string | null, signal: AbortSignal) => call(session, `${base}?${query(cursor)}`, { signal }, value => p.page(value, p.subscription));
export const preference = (session: Session, signal: AbortSignal) => call(session, '/api/knowledge-subscription-preferences', { signal }, p.preference);
export function candidates(session: Session, kind: Kind, text: string, cursor: string | null, signal: AbortSignal) {
  p.requireValue([...p.text(text)].length <= 128 && !/[\u0000-\u001f\u007f]/.test(text));
  return call(session, `${base}/candidates`, json({ kind, query: text, limit: 20, cursor }, signal), value => p.page(value, p.candidate));
}
export const target = (session: Session, documentId: string, signal: AbortSignal) => call(session, `${base}/target-state`, json({ kind: 'DOCUMENT', targetId: p.decimal(documentId, 1) }, signal), value => {
  const result = p.candidate(value); p.requireValue(result.kind === 'DOCUMENT' && result.targetId === documentId); return result;
});
export function write(session: Session, original: Intent, signal: AbortSignal) {
  p.requireValue(intents.has(original));
  return call(session, original.path, { ...json(original.body, signal), method: original.method,
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': original.requestId } }, value => verifyReceipt(p.receipt(value), original), true);
}
export function lookup(session: Session, original: Intent, signal: AbortSignal) {
  p.requireValue(intents.has(original));
  return call(session, `${base}/receipts/query`, json({ requestId: original.requestId }, signal), value => {
    const result = p.lookup(value); p.requireValue(result.requestId === original.requestId);
    if (result.receipt) verifyReceipt(result.receipt, original); return result;
  });
}
export const updates = (session: Session, week: string | null, cursor: string | null, signal: AbortSignal) => call(session,
  `/api/knowledge-updates?${query(cursor)}${week === null ? '' : `&weekStart=${p.week(week)}`}`, { signal }, value => p.page(value, p.update));
export const detail = (session: Session, id: string, signal: AbortSignal) => call(session, `/api/knowledge-updates/${p.uuid(id)}`, { signal }, value => {
  const result = p.detail(value); p.requireValue(result.update.updateId === id); return result;
});
export const digests = (session: Session, cursor: string | null, signal: AbortSignal) => call(session, `/api/knowledge-digests?${query(cursor)}`, { signal }, value => p.page(value, p.digest));
export const digest = (session: Session, id: string, cursor: string | null, signal: AbortSignal) => call(session, `/api/knowledge-digests/${p.uuid(id)}?${query(cursor)}`, { signal }, value => {
  const result = p.digestDetail(value); p.requireValue(result.digest.digestId === id); return result;
});
export const members = (session: Session, id: string, document: string, cursor: string | null, signal: AbortSignal) => call(session,
  `/api/knowledge-digests/${p.uuid(id)}/documents/${p.decimal(document, 1)}?${query(cursor)}`, { signal }, value => p.page(value, p.update));

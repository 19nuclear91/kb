import { ApiError, decodeJson, encodeJson, positiveId, readJson } from './api';
import type { Session } from './session';
import type { Citation } from './types';
import type { TurnBinding } from './logic';

export type StreamEvent = { type: 'delta'; text: string } | { type: 'citation'; citation: Citation }
  | { type: 'done' } | { type: 'error' };
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export async function readSse(response: Response, signal: AbortSignal, emit: (event: StreamEvent) => void,
                              limits = { eventBytes: 1024 * 1024, codepoints: 64000, citations: 256 }): Promise<'done' | 'error' | 'eof'> {
  if (!response.body) throw new ApiError(503);
  const reader = response.body.getReader(), decoder = new TextDecoder('utf-8', { fatal: true }), encoder = new TextEncoder();
  let buffer = '', event = '', data: string[] = [], bytes = 0, points = 0, sources = 0;
  let terminal: 'done' | 'error' | 'eof' = 'eof';
  const check = () => { if (signal.aborted) throw new DOMException('请求已停止', 'AbortError'); };
  const cancel = () => { void reader.cancel().catch(() => {}); };
  signal.addEventListener('abort', cancel, { once: true });
  const dispatch = () => {
    if (!data.length) { event = ''; bytes = 0; return; }
    if (['delta', 'citation', 'done', 'error'].includes(event)) {
      const value = decodeJson<Record<string, unknown>>(data.join('\n'));
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(503);
      if (event === 'delta') {
        if (typeof value.text !== 'string' || (points += [...value.text].length) > limits.codepoints) throw new ApiError(503);
        emit({ type: 'delta', text: value.text });
      } else if (event === 'citation') {
        if (++sources > limits.citations || typeof value.chunkId !== 'string') throw new ApiError(503);
        emit({ type: 'citation', citation: value as unknown as Citation });
      } else { terminal = event as 'done' | 'error'; emit({ type: terminal }); }
    }
    event = ''; data = []; bytes = 0;
  };
  try {
    check();
    while (terminal === 'eof') {
      const chunk = await reader.read(); check();
      if (chunk.done) { buffer += decoder.decode(); break; }
      buffer += decoder.decode(chunk.value, { stream: true });
      let offset: number;
      while ((offset = buffer.indexOf('\n')) >= 0) {
        let line = buffer.slice(0, offset); buffer = buffer.slice(offset + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);
        bytes += encoder.encode(line).length + 1;
        if (bytes > limits.eventBytes) throw new ApiError(503);
        if (line === '') { dispatch(); if (terminal !== 'eof') break; }
        else if (!line.startsWith(':')) {
          const colon = line.indexOf(':'), name = colon < 0 ? line : line.slice(0, colon);
          const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
          if (name === 'event') event = value;
          if (name === 'data') data.push(value);
        }
      }
      if (bytes + encoder.encode(buffer).length > limits.eventBytes) throw new ApiError(503);
    }
    // EOF 不提交残缺帧，也不表示生成成功；调用者重新核对持久轮次。
    return terminal;
  } catch (error) {
    check();
    if (error instanceof ApiError) throw error;
    throw new ApiError(503);
  } finally { signal.removeEventListener('abort', cancel); await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

export async function openTurn(session: Session, conversationId: string, binding: TurnBinding, signal: AbortSignal,
                               started: (id: string) => void, emit: (event: StreamEvent) => void) {
  const response = await session.send(`/api/ai/conversations/${positiveId(conversationId)}/turns`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' },
    body: encodeJson(binding), signal,
  });
  if (!response.ok) { await response.body?.cancel().catch(() => {}); throw new ApiError(response.status); }
  const type = response.headers.get('Content-Type') ?? '';
  if (type.includes('application/json')) {
    const replay = await readJson<{ conversationId: string; turnId: string; replayed: boolean }>(response, false);
    if (replay.replayed !== true || replay.conversationId !== conversationId || !UUID.test(replay.turnId)) throw new ApiError(503);
    started(replay.turnId);
    return 'replay' as const;
  }
  const turnId = response.headers.get('X-Turn-Id');
  if (!type.includes('text/event-stream') || response.headers.get('X-Conversation-Id') !== conversationId || !turnId || !UUID.test(turnId)) {
    await response.body?.cancel().catch(() => {}); throw new ApiError(503);
  }
  started(turnId);
  return readSse(response, signal, emit);
}

import { expect, it, vi } from 'vitest';
import { openTurn, readSse } from './stream';
import { bindTurn } from './logic';
import { json, turn } from './test/fixtures';
import type { Session } from './session';

function response(text: string, split = false) {
  const bytes = new TextEncoder().encode(text);
  return new Response(new ReadableStream<Uint8Array>({ start(target) { if (split) for (const byte of bytes) target.enqueue(new Uint8Array([byte])); else target.enqueue(bytes); target.close(); } }),
    { headers: { 'Content-Type': 'text/event-stream', 'X-Conversation-Id': '1', 'X-Turn-Id': turn.id } });
}
it('SSE 中文 UTF-8、CRLF、跨帧多行 data 和保活注释', async () => {
  const events: unknown[] = [];
  const state = await readSse(response(': 保活\r\n\r\nevent: delta\r\ndata: {\r\ndata: "text":"中文😀"}\r\n\r\nevent: citation\ndata: {"documentId":1,"versionId":2,"chunkId":3}\n\nevent: done\ndata: {}\n\n', true), new AbortController().signal, event => events.push(event));
  expect(state).toBe('done'); expect(events).toEqual([{ type: 'delta', text: '中文😀' }, { type: 'citation', citation: { documentId: '1', versionId: '2', chunkId: '3' } }, { type: 'done' }]);
});
it('error 和无 done EOF 不表示成功，残缺帧不提交', async () => {
  expect(await readSse(response('event: error\ndata: {"message":"private"}\n\n'), new AbortController().signal, vi.fn())).toBe('error');
  const emit = vi.fn(); expect(await readSse(response('event: delta\ndata: {"text":"partial"}'), new AbortController().signal, emit)).toBe('eof'); expect(emit).not.toHaveBeenCalled();
});
it.each(['event: delta\ndata: invalid\n\n', 'event: delta\ndata: {"text":1}\n\n', 'event: citation\ndata: {}\n\n'])('畸形帧中断并交由调用者核对', async text => {
  await expect(readSse(response(text), new AbortController().signal, vi.fn())).rejects.toMatchObject({ status: 503 });
});
it('事件、累计字符、引用数量都受限并取消读取', async () => {
  const signal = new AbortController().signal;
  await expect(readSse(response('event: delta\ndata: {"text":"oversized"}\n\n'), signal, vi.fn(), { eventBytes: 15, codepoints: 100, citations: 1 })).rejects.toBeDefined();
  await expect(readSse(response('event: delta\ndata: {"text":"😀😀"}\n\n'), signal, vi.fn(), { eventBytes: 100, codepoints: 1, citations: 1 })).rejects.toBeDefined();
  await expect(readSse(response('event: citation\ndata: {"chunkId":3}\n\nevent: citation\ndata: {"chunkId":4}\n\n'), signal, vi.fn(), { eventBytes: 100, codepoints: 100, citations: 1 })).rejects.toBeDefined();
});
it('停止取消仍在等待读取的流', async () => {
  const cancel = vi.fn(), controller = new AbortController();
  const operation = readSse(new Response(new ReadableStream({ cancel })), controller.signal, vi.fn());
  const assertion = expect(operation).rejects.toMatchObject({ name: 'AbortError' }); controller.abort(); await assertion; expect(cancel).toHaveBeenCalledOnce();
});
it('请求只包含本轮绑定，JSON replay 只返回持久轮次身份', async () => {
  const request = bindTurn('问题', 9007199254740993n), started = vi.fn(), emit = vi.fn();
  const send = vi.fn().mockResolvedValue(json({ conversationId: 1, turnId: turn.id, replayed: true }, false));
  expect(await openTurn({ send } as unknown as Session, '1', request, new AbortController().signal, started, emit)).toBe('replay');
  expect(started).toHaveBeenCalledWith(turn.id); expect(emit).not.toHaveBeenCalled(); expect(send).toHaveBeenCalledOnce();
  expect(send.mock.calls[0][0]).toBe('/api/ai/conversations/1/turns');
  expect(send.mock.calls[0][1].body).toContain('"expectedLastTurnSeq":9007199254740993');
  expect(JSON.parse(send.mock.calls[0][1].body)).toHaveProperty('question', '问题');
  expect(Object.keys(JSON.parse(send.mock.calls[0][1].body)).sort()).toEqual(['expectedLastTurnSeq', 'question', 'requestId']);
});
it('首次 SSE 按头确定轮次；模型调用前拒绝没有虚构 turnId', async () => {
  const started = vi.fn(), emit = vi.fn(), send = vi.fn().mockResolvedValueOnce(response('event: done\ndata: {}\n\n')).mockResolvedValueOnce(new Response(null, { status: 503 }));
  expect(await openTurn({ send } as unknown as Session, '1', bindTurn('问题', 0n), new AbortController().signal, started, emit)).toBe('done');
  started.mockClear(); await expect(openTurn({ send } as unknown as Session, '1', bindTurn('问题', 0n), new AbortController().signal, started, emit)).rejects.toMatchObject({ status: 503 }); expect(started).not.toHaveBeenCalled();
});

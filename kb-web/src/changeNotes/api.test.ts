import { beforeEach, expect, it, vi } from 'vitest';
import { ApiError, decodeJson, encodeJson } from '../api';
import type { Session } from '../session';
import { note } from '../test/versionDiff';
import { changeNoteIntent, changeNoteWrite, changeNotesApi, decodeHistory, decodeNote } from './api';

const send = vi.fn(), session = { send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session;
const response = (data: unknown, status = 200) => new Response(encodeJson(status === 200 ? { code: 200, data } : data), { status, headers: { 'Content-Type': 'application/json' } });
beforeEach(() => send.mockReset());
it('严格解析说明，拒绝缺失字段、错误类型及不一致的基准', () => {
  expect(decodeNote(note)).toEqual(note);
  for (const patch of [{ corrected: undefined }, { editable: 'true' }, { noteVersion: -1 }, { hasNote: false }, { baseVersionId: null }, { text: '\u0000' }])
    expect(() => decodeNote({ ...note, ...patch })).toThrow(ApiError);
});
it('读取及历史保持长整数 ID、说明版本精度，历史投影排除操作者', async () => {
  const long = '9223372036854775807';
  const value = decodeJson(encodeJson({ ...note, documentId: BigInt(long), versionId: BigInt(long), baseVersionId: 20n, writeBaseVersionId: 20n, noteVersion: BigInt(long) }));
  expect(decodeNote(value)).toMatchObject({ documentId: long, versionId: long, noteVersion: BigInt(long) });
  send.mockResolvedValue(response(value)); expect(await changeNotesApi.get(session, { documentId: long, versionId: long })).toMatchObject({ documentId: long });
  const history = decodeHistory(decodeJson(encodeJson({ items: [{ id: BigInt(long), documentId: 10n, versionId: 21n, changedAt: '2026-11-17T00:00:00Z',
    textBefore: null, textAfter: '说明', baseBefore: null, baseAfter: BigInt(long), visibleToReaders: true, reason: null, actorUserId: 123n }], nextCursor: null })));
  expect(history.items[0]).toMatchObject({ id: long, baseAfter: long }); expect(history.items[0]).not.toHaveProperty('actorUserId');
});
it.each([400, 409, 418, 503])('未知错误 %s 不显示服务器提供的正文或原因', async status => {
  send.mockResolvedValue(response({ code: 500, data: null, reasonCode: 'SECRET', message: '<script>私密</script>' }, status));
  const failure = await changeNotesApi.get(session, note).catch(error => error);
  expect(failure.status).toBe(status); expect(failure.message).not.toContain('私密');
});
it('只接受与 HTTP 状态匹配的原因码', async () => {
  send.mockResolvedValue(response({ code: 500, data: null, reasonCode: 'REASON_REQUIRED' }, 409));
  await expect(changeNotesApi.get(session, note)).rejects.toMatchObject({ reason: 'REASON_REQUIRED' });
  send.mockResolvedValue(response({ code: 500, data: null, reasonCode: 'REASON_REQUIRED' }, 503));
  await expect(changeNotesApi.get(session, note)).rejects.not.toHaveProperty('reason');
});
it('提交按 code point 计长并保留换行，匹配回执和原请求标识', async () => {
  const intent = changeNoteIntent(note, '  第一行\n😀第二行  ', null);
  expect(intent.body).toMatchObject({ text: '第一行\n😀第二行', expectedNoteVersion: 1n });
  expect(() => changeNoteIntent(note, '😀'.repeat(2001), null)).toThrow();
  expect(() => changeNoteIntent(note, '\uD800', null)).toThrow();
  send.mockResolvedValue(response({ documentId: 10n, versionId: 21n, noteVersion: 2, corrected: false, createdAfterVisible: false, updatedAt: '2026-11-17T00:00:00Z', replayed: false }));
  await expect(changeNoteWrite(session, intent, 'original-request')).resolves.toMatchObject({ versionId: '21' });
  expect(send.mock.calls[0]![1].headers['X-Request-Id']).toBe('original-request');
  send.mockResolvedValue(response({ documentId: 10n, versionId: 22n, noteVersion: 2, corrected: false, createdAfterVisible: false, updatedAt: '2026-11-17T00:00:00Z', replayed: false }));
  await expect(changeNoteWrite(session, intent, 'original-request')).rejects.toMatchObject({ status: 503 });
});

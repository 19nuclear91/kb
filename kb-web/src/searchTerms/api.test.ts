import { expect, it, vi } from 'vitest';
import { encodeJson } from '../api';
import type { Session } from '../session';
import { json } from '../test/fixtures';
import { decodeCorrection, decodeReceipt, termApi, termText, termWrites, termWrite, type Term } from './api';

export const term: Term = { termId: '9223372036854775807', canonicalName: '项目管理', description: '公开释义', enabled: true, version: '0',
  canonicalAmbiguous: false, aliases: [{ text: 'PM', kind: 'ABBREVIATION', ambiguous: true }], createdAt: '2026-11-24T00:00:00Z', updatedAt: '2026-11-24T00:00:00Z' };
const client = (value: unknown) => ({ send: vi.fn().mockResolvedValue(json(value)), getSnapshot: () => ({ epoch: 1 }) }) as unknown as Session;
it('词典查询正文与修订、大整数、当前映射及目标状态分别解码', async () => {
  const session = client({ items: [term], hasMore: false, nextCursor: null, dictionaryRevision: '9223372036854775807' });
  const page = await termApi.query(session, 'TERM', { nameQuery: ' %_ ', enabled: true, limit: 20 }, null);
  expect(page.items[0]).toEqual(term); expect(page.dictionaryRevision).toBe('9223372036854775807');
  expect(session.send).toHaveBeenCalledWith('/api/admin/search-terms/terms/query', expect.objectContaining({ method: 'POST', body: '{"nameQuery":"%_","enabled":true,"limit":20}' }), false, false);
  const correction = { correctionId: '1', wrongExpression: '错词', target: { ...term, enabled: false }, enabled: true, available: false, version: '0', createdAt: term.createdAt, updatedAt: term.updatedAt };
  expect(decodeCorrection(correction)).toMatchObject({ enabled: true, target: { enabled: false }, available: false });
  expect(() => decodeCorrection({ ...correction, available: true })).toThrow();
});
it('严格规范化关系输入和精确前置版本，跨组共享别名不被客户端阻止', () => {
  const value = termWrites.term(term, { canonicalName: ' 项目管理 ', description: ' 公开释义 ', aliases: [{ text: ' PM ', kind: 'ABBREVIATION' }] });
  expect(value).toMatchObject({ method: 'PUT', path: '/api/admin/search-terms/terms/9223372036854775807', body: { canonicalName: '项目管理', aliases: [{ text: 'PM', kind: 'ABBREVIATION' }] } });
  expect(encodeJson(termWrites.enabled('TERM', term.termId, '9223372036854775807', false))).toContain('"expectedVersion":9223372036854775807');
  expect(termText('\u3000e\u0301\u00a0')).toBe('é');
  for (const invalid of ['a'.repeat(65), 'x\0', '\ud800']) expect(() => termText(invalid)).toThrow();
  expect(() => termWrites.term(null, { canonicalName: 'Name', description: '释义', aliases: [{ text: 'NAME', kind: 'SYNONYM' }] })).toThrow();
});
it('停用映射允许保留已停用目标，编辑内容须选择启用目标', () => {
  expect(termWrites.enabled('CORRECTION', '1', '0', false).body).toEqual({ enabled: false, expectedVersion: 0n });
  expect(() => termWrites.correction(null, '错词', { ...term, enabled: false })).toThrow();
  expect(encodeJson(termWrites.correction(null, '错词', term).body)).toContain('"termId":9223372036854775807');
});
it('首次回执不同于当前读值，写入路径与原请求标识保留', async () => {
  const receipt = { kind: 'TERM', id: term.termId, version: '0', dictionaryRevision: '1', replayed: true };
  const session = client(receipt), intent = termWrites.term(null, { canonicalName: '项目管理', description: '释义', aliases: [] });
  await expect(termWrite(session, intent, 'original-id')).resolves.toEqual(receipt);
  expect(session.send).toHaveBeenCalledWith(intent.path, expect.objectContaining({ headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'original-id' } }), false, false);
  expect(() => decodeReceipt({ ...receipt, version: '9223372036854775808' })).toThrow();
  await expect(termWrite(client({ ...receipt, kind: 'CORRECTION' }), intent, 'original-id')).rejects.toMatchObject({ status: 0 });
});
it('未知或错误状态的失败码不回显远程文本，404 回执仍不是写入失败证据', async () => {
  const session = client({}); vi.mocked(session.send).mockResolvedValue(new Response(encodeJson({ code: 500, data: null, message: 'PRIVATE', reasonCode: 'NAME_CONFLICT' }), { status: 409, headers: { 'Content-Type': 'application/json' } }));
  await expect(termApi.query(session, 'TERM', { nameQuery: '', limit: 20 }, null)).rejects.toMatchObject({ status: 409, reason: 'NAME_CONFLICT' });
  vi.mocked(session.send).mockResolvedValue(new Response(null, { status: 404 }));
  await expect(termApi.receipt(session, 'original-id')).rejects.toMatchObject({ status: 404 });
});
it('已知 403 不等待错误体或取消完成，立即交付固定资格拒绝', async () => {
  const cancel = vi.fn(() => new Promise<void>(() => {})), pull = vi.fn();
  const response = new Response(new ReadableStream({ pull, cancel }), { status: 403, headers: { 'Content-Type': 'application/json' } });
  const session = client({}); vi.mocked(session.send).mockResolvedValue(response);
  await expect(termApi.query(session, 'TERM', { nameQuery: '', limit: 20 }, null)).rejects.toMatchObject({ status: 403, reason: 'FORBIDDEN' });
  expect(cancel).toHaveBeenCalledOnce(); expect(response.bodyUsed).toBe(true);
});

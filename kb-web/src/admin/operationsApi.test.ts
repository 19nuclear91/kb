import { describe, expect, it, vi } from 'vitest';
import { decodeJson } from '../api';
import type { Session } from '../session';
import { deferred, json } from '../test/fixtures';
import { deadLetterPage, deadLetterPath, operationPaths, operationReason, operationWrites, operationsWrite, type EmbeddingReceipt } from './operationsApi';

const runId = '12345678-1234-1234-1234-123456789012';
const receipt: EmbeddingReceipt = { operationId: runId, runId, profileId: 'p', state: 'QUIESCING', epoch: 9007199254740994n, version: 0, replayed: false };
const dead = { id: '9223372036854775807', taskId: '7', versionId: '8', executionGeneration: 9007199254740993n, attempts: 3, failureClass: 'RETRYABLE', failureCode: 'MODEL_UNAVAILABLE', errorSummary: '安全摘要', status: 'REPLAYED', enteredAt: '2026-10-23T00:00:00Z', resolvedAt: null };
function client(response = json(receipt)) {
  let epoch = 1;
  const send = vi.fn().mockResolvedValue(response), read = vi.fn();
  return { session: { send, json: read, getSnapshot: () => ({ epoch }) } as unknown as Session, send, read, invalidate: () => epoch++ };
}
describe('运维适配器', () => {
  it('无损发送 expectedEpoch、原因和请求标识，匹配回执才确认', async () => {
    const h = client();
    const value = await operationsWrite(h.session, operationWrites.start('p', 9007199254740993n, ' 修复 '), 'request-1');
    expect(value).toEqual(receipt);
    const [path, init] = h.send.mock.calls[0];
    expect(path).toBe(operationPaths.rebuilds);
    expect(decodeJson(init.body)).toEqual({ profileId: 'p', expectedEpoch: 9007199254740993n, reason: '修复' });
    expect(init.headers['X-Request-Id']).toBe('request-1');
  });
  it('精确构造恢复及死信路径，原因采用各自的 code point 上限', () => {
    expect(operationWrites.resume(runId, 9007199254740993n, '恢复')).toMatchObject({ path: `/api/admin/embedding/rebuilds/${runId}/resume`, body: { expectedVersion: 9007199254740993n } });
    expect(operationWrites.replay(dead.id, '😀'.repeat(2000)).path).toBe(`/api/admin/parse/dead-letters/${dead.id}/replay`);
    expect(operationReason('😀'.repeat(500), 500)).toHaveLength(1000);
    for (const value of ['', ' ', '😀'.repeat(501), 'a\0b']) expect(() => operationWrites.start('p', 1, value)).toThrow();
    expect(() => operationWrites.replay('9', 'a'.repeat(2001))).toThrow();
    expect(() => operationWrites.resume('bad', 1, '恢复')).toThrow();
    expect(() => operationWrites.start('p', 9007199254740992, '恢复')).toThrow();
  });
  it('Embedding 标识上限 128，死信上限 256', async () => {
    const a = client(); await expect(operationsWrite(a.session, operationWrites.start('p', 1, '原因'), 'a'.repeat(129))).rejects.toMatchObject({ status: 400 });
    expect(a.send).not.toHaveBeenCalled();
    const b = client(json(dead)); await expect(operationsWrite(b.session, operationWrites.replay(dead.id, '原因'), 'a'.repeat(256))).resolves.toMatchObject({ id: dead.id });
    await expect(operationsWrite(b.session, operationWrites.replay(dead.id, '原因'), 'a'.repeat(257))).rejects.toMatchObject({ status: 400 });
  });
  it('失败不消费 reasonCode 或 message，5xx 保持对应状态', async () => {
    const h = client(json({ code: 500, data: null, reasonCode: 'ROLE_IN_USE', message: '敏感原文' }, false, 409));
    await expect(operationsWrite(h.session, operationWrites.start('p', 1, '原因'), 'id')).rejects.toMatchObject({ status: 409, message: '状态已变化，请刷新后确认。' });
    const down = client(new Response(null, { status: 503 }));
    await expect(operationsWrite(down.session, operationWrites.start('p', 1, '原因'), 'id')).rejects.toMatchObject({ status: 503 });
  });
  it.each([json({ ...receipt, profileId: 'other' }), json({ ...receipt, runId: 'bad' }), json({ code: 500, data: null }, false), new Response('<html>bad</html>')])('不可信成功回执作为结果不确定', async response => {
    const h = client(response);
    await expect(operationsWrite(h.session, operationWrites.start('p', 1, '原因'), 'id')).rejects.toMatchObject({ status: 0 });
  });
  it('死信与恢复回执必须匹配实际目标', async () => {
    const h = client(json({ ...dead, id: '8' }));
    await expect(operationsWrite(h.session, operationWrites.replay(dead.id, '原因'), 'id')).rejects.toMatchObject({ status: 0 });
    const other = client(json(receipt));
    await expect(operationsWrite(other.session, operationWrites.resume('22345678-1234-1234-1234-123456789012', 1, '原因'), 'id')).rejects.toMatchObject({ status: 0 });
  });
  it('会话变化隔离迟到的成功响应', async () => {
    const h = client(), pending = deferred<Response>(); h.send.mockReturnValue(pending.promise);
    const result = operationsWrite(h.session, operationWrites.start('p', 1, '原因'), 'id');
    h.invalidate(); pending.resolve(json(receipt));
    await expect(result).rejects.toMatchObject({ name: 'AbortError' });
  });
  it('列表查询带独立标识，精确保留总数及身份，不要求运行服务器', async () => {
    const h = client(), signal = new AbortController().signal;
    h.read.mockResolvedValue({ page: 1, size: 20, total: 9007199254740993n, items: [dead] });
    const result = await deadLetterPage(h.session, { page: 1, size: 20, status: 'OPEN' }, signal);
    await deadLetterPage(h.session, { page: 1, size: 20 }, signal);
    expect(result.total).toBe(9007199254740993n); expect(result.items[0].id).toBe(dead.id);
    expect(h.read.mock.calls[0][0]).toContain('status=OPEN');
    expect(h.read.mock.calls[0][1].headers['X-Request-Id']).not.toBe(h.read.mock.calls[1][1].headers['X-Request-Id']);
  });
  it('筛选拒绝非法枚举、时间和分页，省略条件保持不限', () => {
    const base = { page: 1, size: 20 };
    expect(deadLetterPath(base)).toBe('/api/admin/parse/dead-letters?page=1&size=20');
    expect(deadLetterPath({ ...base, from: '2026-10-20T08:00:00+08:00', to: '2026-10-21T00:00:00Z' })).toContain('from=');
    for (const extra of [{ page: 0 }, { page: 2147483648 }, { size: 101 }, { status: 'open' }, { failureClass: 'bad' },
      { from: '2026-10-20T08:00' }, { from: '2026-02-30T00:00:00Z' }, { from: '2026-10-20T00:00:00Z', to: '2026-10-20T00:00:00Z' }]) {
      expect(() => deadLetterPath({ ...base, ...extra })).toThrow();
    }
  });
});

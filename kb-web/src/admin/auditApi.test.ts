import { describe, expect, it, vi } from 'vitest';
import * as api from '../api';
import { ApiError, decodeJson, encodeJson, readJson } from '../api';
import type { Session } from '../session';
import { deferred, json } from '../test/fixtures';
import { auditDetail, auditInstant, auditLocalTime, auditPage, auditPath, auditValue, checkAuditWindow } from './auditApi';

const eventId = '12345678-1234-1234-1234-123456789012', signal = new AbortController().signal;
const meta = { id: '9223372036854775807', eventId, operationScope: 'UNKNOWN_SCOPE', requestId: 'req', actorUserId: null,
  targetType: 'PARSE_DEAD_LETTER', targetId: null, operation: 'unknown', status: 'FAILED', createdAt: '2026-10-20T00:00:00Z', attemptedUsername: null };
const window = { from: '2026-10-17T00:00:00.123456Z', to: '2026-10-24T00:00:00.123456Z' };
function client(value: unknown) {
  const read = vi.fn().mockImplementation(async () => json(value)), state = { epoch: 1 };
  return { read, state, session: { send: read, getSnapshot: () => state } as unknown as Session };
}
describe('审计输入输出单元', () => {
  it('有限集合保留精确 ID，空集合和显式未分类独立显示', async () => {
    const c = client({ metadata: meta, summaryAvailable: true, changes: [
      { field: 'tagIds', before: [9223372036854775807n], after: [] },
      { field: 'changedFields', before: [], after: ['title', 'tagIds'] }, { field: 'categoryId', before: null, after: null },
    ] });
    const value = await auditDetail(c.session, eventId, signal);
    expect(auditValue(value.changes[0].before)).toBe('9223372036854775807'); expect(auditValue(value.changes[0].after)).toBe('空集合');
    expect(auditValue(value.changes[2].after, 'categoryId')).toBe('未分类');
    for (const change of [{ field: 'tagIds', before: [{}], after: [] }, { field: 'tagIds', before: ['name'], after: [] },
      { field: 'changedFields', before: ['other'], after: [] }, { field: 'unknown', before: [], after: null }]) {
      const bad = client({ metadata: meta, summaryAvailable: true, changes: [change] });
      await expect(auditDetail(bad.session, eventId, signal)).rejects.toMatchObject({ status: 503 });
    }
  });
  it('列表使用 GET 会话读取，投影安全字段并保留空主体', async () => {
    const c = client({ ...window, items: [{ ...meta, token: 'secret' }], hasMore: false, nextCursor: null, reason: 'secret' });
    const page = await auditPage(c.session, { limit: 20 }, null, signal);
    expect(c.read).toHaveBeenCalledWith('/api/admin/audits?limit=20', { signal }, false, false);
    expect(page).toEqual({ ...window, items: [meta], hasMore: false, nextCursor: null });
  });
  it('沿用 R 解码及完整 Long 整数', async () => {
    const value = await readJson(json({ metadata: { ...meta, id: 9223372036854775807n, actorUserId: 9007199254740993n }, summaryAvailable: true,
      changes: [{ field: 'version', before: 9007199254740993n, after: 9223372036854775807n }] }));
    const c = client(value), result = await auditDetail(c.session, eventId, signal);
    expect(result.metadata.actorUserId).toBe('9007199254740993'); expect(result.metadata.id).toBe('9223372036854775807');
    expect(auditValue(result.changes[0].after)).toBe('9223372036854775807');
  });
  it('精确组合并编码未知代码，空值省略且不补默认时间', () => {
    const path = auditPath({ limit: 50, targetType: 'external-identity', targetId: '9223372036854775807', operationScope: 'Unknown_%',
      operation: 'bind', status: 'FAILED', requestId: 'a&b=中', actorUserId: '9', loginUsername: 'unknown_%' });
    const q = new URL(path, 'https://unit.test').searchParams;
    expect(q.get('requestId')).toBe('a&b=中'); expect(q.get('operationScope')).toBe('Unknown_%'); expect(q.get('targetId')).toBe('9223372036854775807');
    expect(q.get('loginUsername')).toBe('unknown_%'); expect(q.has('from')).toBe(false);
    expect(auditPath({ limit: 20, requestId: '', status: '', targetId: '' })).toBe('/api/admin/audits?limit=20');
  });
  it.each([{ targetId: '1' }, { actorUserId: '0' }, { actorUserId: '9223372036854775808' }, { actorUserId: '1.1' },
    { status: 'success' }, { requestId: ' x' }, { requestId: 'x ' }, { requestId: '\n' }, { targetType: 'x'.repeat(65) },
    { operation: 'x'.repeat(129) }, { operationScope: 'x'.repeat(129) }, { loginUsername: 'x'.repeat(129) }, { limit: 101 }])('非法输入不发请求 %j', async filter => {
    const c = client({}); await expect(auditPage(c.session, { limit: 20, ...filter }, null, signal)).rejects.toMatchObject({ status: 400 }); expect(c.read).not.toHaveBeenCalled();
  });
  it('按 code point 校验文本长度，不静默截断', () => {
    expect(auditPath({ limit: 20, requestId: '😀'.repeat(256) })).toContain('requestId=');
    expect(() => auditPath({ limit: 20, requestId: '😀'.repeat(257) })).toThrow(ApiError);
    expect(() => auditPath({ limit: 20, actorUserId: 9007199254740992 as unknown as string })).toThrow(ApiError);
  });
  it('精确保留微秒窗口与时区等价值', () => {
    expect(auditInstant(window.to)).toBe(auditInstant('2026-10-24T08:00:00.123456+08:00'));
    expect(auditInstant('2026-10-24T00:00:00.123457Z') - auditInstant(window.to)).toBe(1n);
    expect(new URL(auditPath({ limit: 20, ...window }, 'cursor_A'), 'https://unit.test').searchParams.get('to')).toBe(window.to);
    expect(() => checkAuditWindow({ from: '2026-09-23T00:00:00Z', to: '2026-10-24T00:00:00Z' })).not.toThrow();
  });
  it.each([{ from: window.from }, { from: window.to, to: window.from }, { from: window.to, to: window.to },
    { from: '2026-09-01T00:00:00Z', to: window.to }, { from: '2026-02-30T00:00:00Z', to: '2026-03-01T00:00:00Z' },
    { from: '2026-10-20T25:00:00Z', to: window.to }, { from: '2026-10-20T00:00:00', to: window.to }])('拒绝非法窗口 %j', filter => {
    expect(() => auditPath({ limit: 20, ...filter })).toThrow(ApiError);
  });
  it('本地时间转换拒绝日期自动归一化', () => {
    expect(new Date(auditLocalTime('2026-10-20T12:30')).getHours()).toBe(12);
    for (const value of ['2026-02-30T12:30', '2026-10-20T24:01', '', '2026-10-20']) expect(() => auditLocalTime(value)).toThrow(ApiError);
  });
  it.each([{ hasMore: true, nextCursor: null }, { hasMore: false, nextCursor: 'cursor' }, { hasMore: true, nextCursor: 'bad=' },
    { items: [meta, meta] }, { from: 'bad' }, { items: [{ ...meta, id: 9007199254740992 }] }, { items: [{ ...meta, eventId: 'bad' }] },
    { items: [{ ...meta, createdAt: window.to }] }])('损坏成功页不当空结果 %j', async patch => {
    const value = { ...window, items: [meta], hasMore: false, nextCursor: null, ...patch }, c = client(value);
    // 在解码边界注入非法结果，避免不安全 Number 被无损 JSON 解码重新转为有效 ID。
    const decoded = vi.spyOn(api, 'readJson').mockImplementationOnce(async response => { await response.body?.cancel(); return value; });
    try { await expect(auditPage(c.session, { limit: 20 }, null, signal)).rejects.toMatchObject({ status: 503 }); }
    finally { decoded.mockRestore(); }
  });
  it('详情值区分空值、零和布尔；不复制额外字段', async () => {
    const values = [false, 0, '', null, 'UNKNOWN_CODE', '<script>x</script>'];
    const c = client({ metadata: meta, summaryAvailable: true, reason: 'secret', changes: values.map(value => ({ field: 'x', before: value, after: null, token: 'secret' })) });
    const detail = await auditDetail(c.session, eventId, signal);
    expect(detail.changes.map(change => auditValue(change.before))).toEqual(['false', '0', '空字符串', '空值', 'UNKNOWN_CODE', '<script>x</script>']);
    expect(detail).not.toHaveProperty('reason'); expect(detail.changes[0]).not.toHaveProperty('token');
  });
  it.each([{}, [], 1.1, 9007199254740992, 9223372036854775808n])('拒绝非标量或不精确变化值 %s', async value => {
    const data = { metadata: meta, summaryAvailable: true, changes: [{ field: 'version', before: value, after: null }] }, c = client(data);
    const decoded = vi.spyOn(api, 'readJson').mockImplementationOnce(async response => { await response.body?.cancel(); return data; });
    try { await expect(auditDetail(c.session, eventId, signal)).rejects.toMatchObject({ status: 503 }); }
    finally { decoded.mockRestore(); }
  });
  it('摘要不可用时忽略内容；UUID 不匹配或无效时拒绝', async () => {
    const c = client({ metadata: meta, summaryAvailable: false, changes: [{ raw: 'secret' }] });
    expect((await auditDetail(c.session, eventId, signal)).changes).toEqual([]);
    await expect(auditDetail(c.session, '22345678-1234-1234-1234-123456789012', signal)).rejects.toMatchObject({ status: 503 });
    c.read.mockClear(); await expect(auditDetail(c.session, 'bad', signal)).rejects.toMatchObject({ status: 400 }); expect(c.read).not.toHaveBeenCalled();
  });
  it('只保留安全 HTTP 错误分类', async () => {
    const c = client({}); c.read.mockRejectedValue(new ApiError(503));
    await expect(auditPage(c.session, { limit: 20 }, null, signal)).rejects.toMatchObject({ status: 503, message: '服务暂不可用，请稍后重试。' });
    await expect(readJson(json({ message: 'secret', reason: 'raw' }, false, 403))).rejects.toThrow('当前操作被拒绝。');
    expect(decodeJson<{ before: bigint }>('{"before":9007199254740993}').before).toBe(9007199254740993n);
  });
  it.each(['page', 'detail'])('403 不等待慢身份核对或错误体取消：%s', async target => {
    const identity = deferred<Response>(), cancelled = deferred<void>(), cancel = vi.fn(() => cancelled.promise);
    const denied = new Response(new ReadableStream({ cancel }), { status: 403 });
    // 模拟 Session.send 的前置核对：仅显式关闭 check403 才会直接交付原响应。
    const read = vi.fn((_path: string, _options: RequestInit, _anonymous: boolean, check403 = true) => check403 ? identity.promise : Promise.resolve(denied));
    const session = { send: read, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session, observed = vi.fn();
    const request = target === 'page' ? auditPage(session, { limit: 20 }, null, signal) : auditDetail(session, eventId, signal);
    const settled = request.then(() => observed('success'), error => observed(error.status));
    try {
      await new Promise(resolve => setTimeout(resolve, 0));
      expect(observed).toHaveBeenCalledWith(403); expect(cancel).toHaveBeenCalledTimes(1);
      expect(read).toHaveBeenCalledWith(expect.stringContaining('/api/admin/audits'), { signal }, false, false);
    } finally { identity.resolve(json({})); cancelled.resolve(); await settled; }
  });
  it.each(['epoch', 'abort'])('响应到达前上下文变化拒绝旧资料：%s', async change => {
    const c = client({}), response = deferred<Response>(), controller = new AbortController();
    c.read.mockReturnValueOnce(response.promise);
    const request = auditPage(c.session, { limit: 20 }, null, controller.signal);
    if (change === 'epoch') c.state.epoch++; else controller.abort();
    const received = json({ ...window, items: [meta], hasMore: false, nextCursor: null }), cancel = vi.spyOn(received.body!, 'cancel');
    response.resolve(received); await expect(request).rejects.toMatchObject({ name: 'AbortError' }); expect(cancel).toHaveBeenCalled();
  });
  it.each(['epoch', 'abort'])('响应体读取期间上下文变化仍拒绝：%s', async change => {
    const c = client({}), body = deferred<string>(), controller = new AbortController();
    const value = { ...window, items: [meta], hasMore: false, nextCursor: null }, response = json(value);
    const readBody = vi.spyOn(response, 'text').mockReturnValue(body.promise); c.read.mockResolvedValueOnce(response);
    const request = auditPage(c.session, { limit: 20 }, null, controller.signal);
    await new Promise(resolve => setTimeout(resolve, 0)); expect(readBody).toHaveBeenCalled();
    if (change === 'epoch') c.state.epoch++; else controller.abort();
    body.resolve(encodeJson({ code: 200, data: value }));
    await expect(request).rejects.toMatchObject({ name: 'AbortError' }); await response.body?.cancel();
  });
});

import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type { Session } from '../session';
import { json } from '../test/fixtures';
import { aiEvent, aiMeta, aiMetrics } from '../test/aiUsageFixtures';
import { aiDefaults, aiDetail, aiOverview, aiPage, aiPath } from './aiUsageApi';
const signal = new AbortController().signal;
function client(value: unknown) { const send = vi.fn<Session['send']>(async () => json(value)); return { send, session: { send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session }; }

describe('AI 专用协议边界', () => {
  it('超 Long 聚合保持字符串，缺少已知样本保持 null', async () => {
    const metrics = { ...aiMetrics(), plannedChargedTokens: '18446744073709551617', promptTokens: null, completionTokens: null };
    const c = client({ ...aiMeta, metrics }); const result = await aiOverview(c.session, aiDefaults(), signal);
    expect(result.metrics).toEqual(metrics); expect(c.send).toHaveBeenCalledWith('/api/admin/ai/usage', { signal, cache: 'no-store' }, false, false);
  });
  it('已知零不会显示为缺省未知，旧审计路径保持独立', async () => {
    const c = client({ ...aiMeta, metrics: { ...aiMetrics(), promptTokens: '0', completionTokens: '0' } });
    expect((await aiOverview(c.session, aiDefaults(), signal)).metrics.promptTokens).toBe('0');
    expect(aiPath('audits', { ...aiDefaults(), callId: aiEvent().callId, usageStatus: 'UNKNOWN' })).toContain('/api/admin/ai/audits?');
    expect(aiPath('usage', aiDefaults())).not.toContain('limit');
  });
  it.each([{ userId: '0' }, { userId: '01' }, { userId: '9223372036854775808' }, { departmentId: 'a' }, { mode: 'invalid' },
    { limit: 101 }, { groupBy: 'bad' as 'day' }, { from: aiMeta.from }, { from: '2026-10-17T00:00:00', to: aiMeta.to },
    { from: '2026-09-01T00:00:00Z', to: aiMeta.to }])('非法条件在请求前拒绝：%j', async patch => {
    const c = client({}); await expect(aiPage(c.session, 'audits', { ...aiDefaults(), ...patch }, null, signal)).rejects.toMatchObject({ status: 400 }); expect(c.send).not.toHaveBeenCalled();
  });
  it('详情只投影安全字段并核对事件身份', async () => {
    const source = aiEvent(); const c = client({ ...source, token: 'private', snapshot: { ...source.snapshot, question: 'private', leaseId: 'private' } });
    const result = await aiDetail(c.session, source.eventId, signal);
    expect(JSON.stringify(result)).not.toContain('private'); expect(result.snapshot.promptTokens).toBeNull();
    await expect(aiDetail(c.session, aiEvent(2).eventId, signal)).rejects.toMatchObject({ status: 503 });
  });
  it.each([{ nextCursor: null, hasMore: true }, { nextCursor: 'bad=', hasMore: true }, { nextCursor: 'cursor', hasMore: false },
    { items: [{ ...aiEvent(), occurredAt: aiMeta.to }] }, { items: [{ ...aiEvent(), snapshot: { ...aiEvent().snapshot, resultCode: { raw: 'private' } } }] }])('损坏页不转换为空结果：%j', async patch => {
    const c = client({ ...aiMeta, items: [aiEvent()], hasMore: false, nextCursor: null, ...patch });
    await expect(aiPage(c.session, 'audits', aiDefaults(), null, signal)).rejects.toMatchObject({ status: 503 });
  });
  it('分组核对 UTC 日与分组方式，标识值不通过 Number 转换', async () => {
    const c = client({ ...aiMeta, groupBy: 'department', items: [{ ...aiMetrics(), key: '9007199254740993' }], hasMore: false, nextCursor: null });
    expect((await aiPage(c.session, 'usage', { ...aiDefaults(), groupBy: 'department' }, null, signal)).items[0]).toMatchObject({ key: '9007199254740993' });
    await expect(aiPage(c.session, 'usage', aiDefaults(), null, signal)).rejects.toMatchObject({ status: 503 });
  });
  it('403 立即交付清理，不等待错误体或自动核对', async () => {
    const c = client({}); c.send.mockImplementation(async () => new Response('private', { status: 403 }));
    await expect(aiPage(c.session, 'audits', aiDefaults(), null, signal)).rejects.toEqual(new ApiError(403));
    expect(c.send.mock.calls[0][3]).toBe(false);
  });
  it('保留安全 DTO 中未知枚举文本，输入筛选仍只接受约定枚举', async () => {
    const source = aiEvent(), c = client({ ...source, eventType: 'FUTURE_PHASE', snapshot: { ...source.snapshot, resultCode: '<script>new</script>' } });
    const result = await aiDetail(c.session, source.eventId, signal);
    expect(result.eventType).toBe('FUTURE_PHASE'); expect(result.snapshot.resultCode).toBe('<script>new</script>');
    expect(() => aiPath('audits', { ...aiDefaults(), eventType: 'FUTURE_PHASE' })).toThrow(ApiError);
  });
});

import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { deferred } from '../test/fixtures';
import { aiEvent, aiMeta, aiOverviewData, aiPageData } from '../test/aiUsageFixtures';
import type { AiEvent, AiPage } from './aiUsageApi';
const h = vi.hoisted(() => ({ epoch: 1, facts: 'ai:usage:read,ai:audit:read', status: 'ready', phase: 'ready', allowed: true, check: vi.fn(), session: { getSnapshot: () => ({ epoch: h.epoch }) } }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session: h.session, state: { epoch: h.epoch, phase: h.phase } }) }));
vi.mock('./access', () => ({ useAdminAccess: () => ({ status: h.status, permissionFactsKey: h.facts, has: () => h.allowed, check: h.check }) }));
vi.mock('./aiUsageApi', async original => ({ ...await original<typeof import('./aiUsageApi')>(), aiPage: vi.fn(), aiOverview: vi.fn(), aiDetail: vi.fn() }));
import { aiDetail, aiOverview, aiPage } from './aiUsageApi';
import { useAiUsageQuery } from './aiUsageQuery';
beforeEach(() => {
  h.epoch++; h.facts = 'ai:usage:read,ai:audit:read'; h.status = 'ready'; h.phase = 'ready'; h.allowed = true; h.check.mockReset();
  vi.mocked(aiPage).mockReset().mockResolvedValue(aiPageData()); vi.mocked(aiOverview).mockReset().mockResolvedValue(aiOverviewData());
  vi.mocked(aiDetail).mockReset().mockResolvedValue(aiEvent());
});
const loaded = async (result: { current: ReturnType<typeof useAiUsageQuery> }) => waitFor(() => expect(result.current.items).toHaveLength(1));

describe('AI 查询代次与生命周期', () => {
  it('续页固定微秒窗口、原提交筛选和一次请求', async () => {
    const { result } = renderHook(() => useAiUsageQuery('audits')); await loaded(result);
    const pending = deferred<AiPage>(); vi.mocked(aiPage).mockReturnValueOnce(pending.promise);
    act(() => { result.current.more(); result.current.more(); });
    expect(aiPage).toHaveBeenCalledTimes(2); expect(vi.mocked(aiPage).mock.calls.at(-1)?.[2]).toEqual({ limit: 20, groupBy: 'day', from: aiMeta.from, to: aiMeta.to });
    await act(async () => pending.resolve({ ...aiPageData('audits', false), items: [aiEvent(2)] }));
    expect(result.current.items).toHaveLength(2); expect(aiOverview).not.toHaveBeenCalled();
  });
  it('新提交中止原请求，迟到页不能恢复旧条件', async () => {
    const pending = deferred<AiPage>(); vi.mocked(aiPage).mockReturnValueOnce(pending.promise);
    const { result } = renderHook(() => useAiUsageQuery('audits')), oldSignal = vi.mocked(aiPage).mock.calls[0][4];
    act(() => result.current.submit({ limit: 20, groupBy: 'day', userId: '9' })); await loaded(result); expect(oldSignal.aborted).toBe(true);
    await act(async () => pending.resolve({ ...aiPageData('audits', false), items: [aiEvent(2)] }));
    expect(result.current.filters.userId).toBe('9'); expect(result.current.items[0]).toMatchObject({ eventId: aiEvent().eventId });
  });
  it.each(['duplicate', 'window', 'cursor'])('拒绝异常续页：%s', async reason => {
    const { result } = renderHook(() => useAiUsageQuery('audits')); await loaded(result);
    const page = { ...aiPageData(), items: [aiEvent(2)], nextCursor: 'cursor_B' };
    if (reason === 'duplicate') page.items = [aiEvent()];
    if (reason === 'window') page.to = '2026-10-24T00:00:00.123457Z';
    if (reason === 'cursor') page.nextCursor = 'cursor_A';
    vi.mocked(aiPage).mockResolvedValueOnce(page); act(() => result.current.more());
    await waitFor(() => expect(result.current.status).toBe(503)); expect(result.current.items).toHaveLength(1);
  });
  it('汇总与分组使用相同窗口，后续分组保留每页独立 asOf', async () => {
    vi.mocked(aiPage).mockResolvedValue(aiPageData('usage'));
    const { result } = renderHook(() => useAiUsageQuery('usage')); await loaded(result);
    expect(aiOverview).toHaveBeenCalledTimes(1);
    expect(vi.mocked(aiPage).mock.calls[0][2]).toMatchObject({ from: aiMeta.from, to: aiMeta.to });
    vi.mocked(aiPage).mockResolvedValueOnce({ ...aiPageData('usage', false), asOf: '2026-10-24T00:01:00Z', items: [{ key: '2026-10-21', metrics: aiOverviewData().metrics, asOf: '2026-10-24T00:01:00Z' }] });
    act(() => result.current.more()); await waitFor(() => expect(result.current.items).toHaveLength(2));
    expect(result.current.overview?.asOf).toBe(aiMeta.asOf); expect(aiOverview).toHaveBeenCalledTimes(1);
  });
  it('403 清空全部资料并跨重挂载最多核对一次', async () => {
    const first = renderHook(() => useAiUsageQuery('audits')); await loaded(first.result);
    vi.mocked(aiPage).mockRejectedValueOnce(new ApiError(403)); act(() => first.result.current.more());
    await waitFor(() => expect(first.result.current.forbidden).toBe(true));
    expect(first.result.current.items).toEqual([]); expect(first.result.current.filters).toEqual({ limit: 20, groupBy: 'day' }); expect(h.check).toHaveBeenCalledTimes(1);
    const count = vi.mocked(aiPage).mock.calls.length; first.unmount(); const second = renderHook(() => useAiUsageQuery('audits'));
    expect(second.result.current.forbidden).toBe(true); expect(aiPage).toHaveBeenCalledTimes(count); expect(h.check).toHaveBeenCalledTimes(1);
    act(() => second.result.current.retry()); await loaded(second.result);
  });
  it('详情 404 保留列表，错误关联拒绝显示，关闭取消迟到结果', async () => {
    const { result } = renderHook(() => useAiUsageQuery('audits')); await loaded(result);
    vi.mocked(aiDetail).mockRejectedValueOnce(new ApiError(404)); act(() => result.current.open(aiEvent().eventId));
    await waitFor(() => expect(result.current.detail?.status).toBe(404)); expect(result.current.items).toHaveLength(1);
    vi.mocked(aiDetail).mockResolvedValueOnce({ ...aiEvent(), callId: aiEvent(2).eventId }); act(() => result.current.open(aiEvent().eventId));
    await waitFor(() => expect(result.current.detail?.status).toBe(503));
    const pending = deferred<AiEvent>(); vi.mocked(aiDetail).mockReturnValueOnce(pending.promise); act(() => result.current.open(aiEvent().eventId));
    const signal = vi.mocked(aiDetail).mock.calls.at(-1)![2]; act(() => result.current.close()); expect(signal.aborted).toBe(true);
    await act(async () => pending.resolve(aiEvent())); expect(result.current.detail).toBeNull();
  });
  it.each(['loading', 'password', 'anonymous'])('身份进入 %s 时清理并恢复默认窗口', async phase => {
    const { result, rerender } = renderHook(() => useAiUsageQuery('audits')); await loaded(result);
    act(() => result.current.submit({ limit: 50, groupBy: 'day', userId: '9' })); await loaded(result);
    h.phase = phase; rerender(); expect(result.current.items).toEqual([]); expect(result.current.filters.userId).toBeUndefined();
    h.epoch++; h.phase = 'ready'; rerender(); await loaded(result); expect(result.current.filters.limit).toBe(20);
  });
  it('资格未确认不预取，503 保留显式重试且不显示零', async () => {
    h.allowed = false; const { result, rerender } = renderHook(() => useAiUsageQuery('usage')); expect(aiOverview).not.toHaveBeenCalled(); expect(aiPage).not.toHaveBeenCalled();
    h.allowed = true; vi.mocked(aiOverview).mockRejectedValueOnce(new ApiError(503)); rerender();
    await waitFor(() => expect(result.current.status).toBe(503)); expect(result.current.overview).toBeUndefined();
    vi.mocked(aiPage).mockResolvedValue(aiPageData('usage')); act(() => result.current.retry()); await loaded(result);
  });
  it('详情拒绝取消并发续页，离页取消未完成请求', async () => {
    const { result, unmount } = renderHook(() => useAiUsageQuery('audits')); await loaded(result);
    const pending = deferred<AiPage>(); vi.mocked(aiPage).mockReturnValueOnce(pending.promise); act(() => result.current.more());
    const signal = vi.mocked(aiPage).mock.calls.at(-1)![4];
    vi.mocked(aiDetail).mockRejectedValueOnce(new ApiError(403)); act(() => result.current.open(aiEvent().eventId));
    await waitFor(() => expect(result.current.forbidden).toBe(true)); expect(signal.aborted).toBe(true);
    await act(async () => pending.resolve({ ...aiPageData('audits', false), items: [aiEvent(2)] })); expect(result.current.items).toEqual([]);
    unmount(); h.epoch++;
    const next = deferred<AiPage>(); vi.mocked(aiPage).mockReturnValueOnce(next.promise);
    const remount = renderHook(() => useAiUsageQuery('audits')), lastSignal = vi.mocked(aiPage).mock.calls.at(-1)![4];
    remount.unmount(); expect(lastSignal.aborted).toBe(true);
    await act(async () => next.resolve(aiPageData()));
  });
});

import { nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { waitFor } from '@testing-library/vue';
import { renderHook } from '../test/render';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { deferred } from '../test/fixtures';
import { operationsReportData } from '../test/operationsReportsFixtures';
import type { OperationsReport } from './operationsReportsApi';
const h = reactive({ epoch: 1, facts: 'operations:reports:read', status: 'ready', phase: 'ready', allowed: true, check: vi.fn(), session: { getSnapshot: () => ({ epoch: h.epoch }) } });
vi.mock('../SessionProvider', () => ({ useSession: () => ({ get session() { return h.session; }, get state() { return { epoch: h.epoch, phase: h.phase }; } }) }));
vi.mock('./access', () => ({ perm: { operationsReportsRead: 'operations:reports:read' }, useAdminAccess: () => ({ get status() { return h.status; }, get permissionFactsKey() { return h.facts; }, has: () => h.allowed, check: (...args: unknown[]) => h.check(...args) }) }));
vi.mock('./operationsReportsApi', async original => ({ ...await original<typeof import('./operationsReportsApi')>(), operationsReport: vi.fn() }));
import { operationsReport } from './operationsReportsApi';
import { useOperationsReportsQuery } from './operationsReportsQuery';
beforeEach(() => {
  h.epoch++; h.facts = 'operations:reports:read'; h.status = 'ready'; h.phase = 'ready'; h.allowed = true; h.check.mockReset(); Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' }); vi.mocked(operationsReport).mockReset().mockResolvedValue(operationsReportData());
});
const loaded = async (result: { current: ReturnType<typeof useOperationsReportsQuery> }) => waitFor(() => expect(result.current.data).toBeDefined());
describe('运营报表查询生命周期', () => {
  it('默认只读一次，显式提交／刷新／重置，不轮询', async () => {
    const { result } = renderHook(useOperationsReportsQuery); await loaded(result); expect(vi.mocked(operationsReport).mock.calls[0][1]).toEqual({}); const filters = { from: '2026-10-01T00:00:00Z', to: '2026-10-02T00:00:00Z' }; result.current.submit(filters); await loaded(result); result.current.refresh(); await loaded(result); expect(vi.mocked(operationsReport).mock.calls.at(-1)![1]).toEqual(filters); result.current.reset(); await loaded(result); expect(result.current.filters).toEqual({}); expect(operationsReport).toHaveBeenCalledTimes(4);
  });
  it('新条件中止旧请求，迟到结果不能覆盖新条件', async () => {
    const pending = deferred<OperationsReport>(); vi.mocked(operationsReport).mockReturnValueOnce(pending.promise); const { result } = renderHook(useOperationsReportsQuery), signal = vi.mocked(operationsReport).mock.calls[0][2]; const filters = { from: '2026-10-01T00:00:00Z', to: '2026-10-02T00:00:00Z' }; result.current.submit(filters); await loaded(result); expect(signal.aborted).toBe(true); const late = operationsReportData(); late.inventory.documentCount = '999'; pending.resolve(late); await flushPromises(); expect(result.current.filters).toEqual(filters); expect(result.current.data?.inventory.documentCount).toBe('2');
  });
  it('新查询 503 遮蔽旧成功结果，可显式重试', async () => {
    const { result } = renderHook(useOperationsReportsQuery); await loaded(result); vi.mocked(operationsReport).mockRejectedValueOnce(new ApiError(503)); result.current.refresh(); expect(result.current.data).toBeUndefined(); await waitFor(() => expect(result.current.status).toBe(503)); result.current.retry(); await loaded(result);
  });
  it.each(['loading', 'password', 'anonymous'])('身份进入 %s 清理数据、筛选和未完成请求', async phase => {
    const { result, rerender } = renderHook(useOperationsReportsQuery); await loaded(result); result.current.submit({ from: '2026-10-01T00:00:00Z', to: '2026-10-02T00:00:00Z' }); await loaded(result); h.phase = phase; h.epoch++; await rerender(); expect(result.current.data).toBeUndefined(); expect(result.current.filters).toEqual({}); h.phase = 'ready'; await rerender(); await loaded(result); expect(vi.mocked(operationsReport).mock.calls.at(-1)![1]).toEqual({});
  });
  it('权限撤销和未知资格不发请求，空间目录事实不影响报表', async () => {
    h.allowed = false; const { result, rerender } = renderHook(useOperationsReportsQuery); expect(operationsReport).not.toHaveBeenCalled(); h.allowed = true; h.status = 'unavailable'; await rerender(); expect(operationsReport).not.toHaveBeenCalled(); h.status = 'ready'; await rerender(); await loaded(result); h.facts = ''; h.allowed = false; await rerender(); expect(result.current.data).toBeUndefined(); expect(result.current.filters).toEqual({});
  });
  it('403 清空筛选并核对一次，重挂载不以旧权限重读', async () => {
    vi.mocked(operationsReport).mockRejectedValueOnce(new ApiError(403)); const first = renderHook(useOperationsReportsQuery); await waitFor(() => expect(first.result.current.forbidden).toBe(true)); expect(first.result.current.filters).toEqual({}); expect(h.check).toHaveBeenCalledTimes(1); first.unmount(); const next = renderHook(useOperationsReportsQuery); expect(next.result.current.forbidden).toBe(true); expect(operationsReport).toHaveBeenCalledTimes(1); h.epoch++; await next.rerender(); await loaded(next.result);
  });
  it('隐藏立即取消并清空，恢复先重核权限再默认读取', async () => {
    const { result, rerender } = renderHook(useOperationsReportsQuery); await loaded(result); const pending = deferred<OperationsReport>(); vi.mocked(operationsReport).mockReturnValueOnce(pending.promise); result.current.submit({ from: '2026-10-01T00:00:00Z', to: '2026-10-02T00:00:00Z' }); const signal = vi.mocked(operationsReport).mock.calls.at(-1)![2]; Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); document.dispatchEvent(new Event('visibilitychange'));
    expect(signal.aborted).toBe(true); expect(result.current.data).toBeUndefined(); expect(result.current.filters).toEqual({}); pending.resolve(operationsReportData()); await flushPromises(); expect(result.current.data).toBeUndefined(); const count = vi.mocked(operationsReport).mock.calls.length; Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' }); document.dispatchEvent(new Event('visibilitychange')); await nextTick(); expect(h.check).toHaveBeenCalledWith(true); expect(operationsReport).toHaveBeenCalledTimes(count); h.status = 'loading'; await rerender(); h.status = 'ready'; await rerender(); await loaded(result); expect(vi.mocked(operationsReport).mock.calls.at(-1)![1]).toEqual({});
  });
  it('离开页面中止读取，恢复后撤权不能显示旧资料', async () => {
    const pending = deferred<OperationsReport>(); vi.mocked(operationsReport).mockReturnValueOnce(pending.promise); const first = renderHook(useOperationsReportsQuery), signal = vi.mocked(operationsReport).mock.calls[0][2]; first.unmount(); expect(signal.aborted).toBe(true); h.epoch++; h.allowed = false; const next = renderHook(useOperationsReportsQuery); pending.resolve(operationsReportData()); await flushPromises(); expect(next.result.current.data).toBeUndefined(); expect(operationsReport).toHaveBeenCalledTimes(1);
  });
});

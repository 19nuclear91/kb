import { reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { beforeEach, expect, it, vi } from 'vitest';
import { renderHook } from '../test/render';
import { deferred, json } from '../test/fixtures';
import { healthFixture } from '../test/healthFixtures';
import { ApiError } from '../api';

const h = reactive({ epoch: 1, id: '2', phase: 'ready', status: 'ready', allowed: true, facts: 'ready', check: vi.fn(), send: vi.fn() });
const session = { getSnapshot: () => ({ epoch: h.epoch }), send: (...args: unknown[]) => h.send(...args) };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, get state() { return { epoch: h.epoch, phase: h.phase }; } }) }));
vi.mock('./access', () => ({ perm: { healthRead: 'knowledge:health:read' }, useAdminAccess: () => ({ get status() { return h.status; }, get factsKey() { return h.facts; }, has: () => h.allowed, check: (...args: unknown[]) => h.check(...args) }) }));
vi.mock('./healthApi', async original => ({ ...await original<typeof import('./healthApi')>(), healthReport: vi.fn() }));
import { healthReport, HealthError, type HealthReport } from './healthApi';
import { useHealthQuery } from './healthQuery';

beforeEach(() => {
  h.epoch++; h.id = '2'; h.phase = 'ready'; h.status = 'ready'; h.allowed = true; h.facts = 'ready'; h.check.mockReset();
  h.send.mockReset().mockImplementation(async () => json({ spaceId: h.id, manageable: true, archived: false }));
  vi.mocked(healthReport).mockReset().mockResolvedValue(healthFixture());
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
});
const show = () => renderHook(() => useHealthQuery(() => h.id));
it('资格未确认或目标不可管理不读取诊断', async () => {
  h.allowed = false; const hook = show(); await flushPromises(); expect(h.send).not.toHaveBeenCalled();
  h.send.mockImplementation(async () => json({ spaceId: '2', manageable: false, archived: false })); h.allowed = true;
  await flushPromises(); expect(healthReport).not.toHaveBeenCalled(); expect(h.check).toHaveBeenCalledOnce(); hook.unmount();
});
it('默认一次读取、筛选首页和故障重试，旧数据立即清除', async () => {
  const hook = show(); await flushPromises(); expect(hook.result.current.data).toBeDefined(); expect(healthReport).toHaveBeenCalledOnce();
  vi.mocked(healthReport).mockRejectedValueOnce(new ApiError(503)); hook.result.current.select('REVIEW_OVERDUE');
  expect(hook.result.current.data).toBeUndefined(); await flushPromises(); expect(hook.result.current.status).toBe(503);
  hook.result.current.retry(); await flushPromises(); expect(vi.mocked(healthReport).mock.calls.at(-1)![2]).toBe('REVIEW_OVERDUE'); hook.unmount();
});
it('换空间取消旧请求，迟到成功不恢复旧身份资料', async () => {
  const pending = deferred<HealthReport>(); vi.mocked(healthReport).mockReturnValueOnce(pending.promise);
  const hook = show(); await flushPromises(); const signal = vi.mocked(healthReport).mock.calls[0][4];
  h.id = '3'; await flushPromises(); expect(signal.aborted).toBe(true);
  pending.resolve({ ...healthFixture(), affectedDocumentCount: '999' }); await flushPromises();
  expect(vi.mocked(healthReport).mock.calls.at(-1)![1]).toBe('3'); expect(hook.result.current.data?.affectedDocumentCount).toBe('1'); hook.unmount();
});
it('归档和跨日分别恢复，跨日重新从首页读取', async () => {
  vi.mocked(healthReport).mockRejectedValueOnce(new HealthError(409, 'RADAR_DATE_CHANGED'));
  const hook = show(); await flushPromises(); expect(hook.result.current.reason).toBe('RADAR_DATE_CHANGED');
  hook.result.current.retry(); await flushPromises(); expect(vi.mocked(healthReport).mock.calls.at(-1)![3]).toBeNull();
  h.send.mockImplementation(async () => json({ spaceId: '2', manageable: true, archived: true }));
  hook.result.current.refresh(); await flushPromises(); expect(hook.result.current.reason).toBe('SPACE_ARCHIVED'); expect(healthReport).toHaveBeenCalledTimes(2); hook.unmount();
});
it('403重核和重挂载不自动循环请求，显式重试解除拒绝', async () => {
  vi.mocked(healthReport).mockRejectedValueOnce(new ApiError(403));
  const first = show(); await flushPromises(); expect(h.check).toHaveBeenCalledOnce(); first.unmount();
  const next = show(); await flushPromises(); expect(healthReport).toHaveBeenCalledOnce(); expect(next.result.current.data).toBeUndefined();
  h.check.mockImplementation(() => { h.status = 'loading'; }); next.result.current.retry(); h.status = 'ready';
  await flushPromises(); expect(healthReport).toHaveBeenCalledTimes(2); next.unmount();
});
it('隐藏清空并在恢复后先重核权限，换号丢弃筛选', async () => {
  const hook = show(); await flushPromises(); hook.result.current.select('REVIEW_OVERDUE'); await flushPromises();
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); document.dispatchEvent(new Event('visibilitychange'));
  expect(hook.result.current.data).toBeUndefined(); expect(hook.result.current.filter).toBe('ALL');
  const count = vi.mocked(healthReport).mock.calls.length;
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' }); document.dispatchEvent(new Event('visibilitychange'));
  await flushPromises(); expect(healthReport).toHaveBeenCalledTimes(count);
  h.status = 'loading'; h.status = 'ready'; await flushPromises(); expect(healthReport).toHaveBeenCalledTimes(count + 1);
  h.epoch++; h.phase = 'password'; await flushPromises(); expect(hook.result.current.data).toBeUndefined(); hook.unmount();
});
it('下一页替换并在离页时中止请求', async () => {
  const first = healthFixture(); first.hasMore = true; first.nextCursor = 'cursor';
  vi.mocked(healthReport).mockResolvedValueOnce(first);
  const hook = show(); await flushPromises(); const pending = deferred<HealthReport>(); vi.mocked(healthReport).mockReturnValueOnce(pending.promise);
  hook.result.current.next(); await flushPromises(); expect(hook.result.current.data).toBeUndefined();
  expect(vi.mocked(healthReport).mock.calls.at(-1)![3]).toBe('cursor'); const signal = vi.mocked(healthReport).mock.calls.at(-1)![4];
  hook.unmount(); expect(signal.aborted).toBe(true); pending.resolve(healthFixture()); await flushPromises();
});
it('换号后迟到拒绝不能清除新结果或发起旧身份重核', async () => {
  const pending = deferred<HealthReport>(); vi.mocked(healthReport).mockReturnValueOnce(pending.promise);
  const hook = show(); await flushPromises(); const signal = vi.mocked(healthReport).mock.calls[0][4];
  h.epoch++; await flushPromises(); expect(signal.aborted).toBe(true); expect(hook.result.current.data).toBeDefined();
  pending.reject(new ApiError(403)); await flushPromises();
  expect(hook.result.current.data).toBeDefined(); expect(h.check).not.toHaveBeenCalled(); hook.unmount();
});
it('资格事实改变使旧请求失效并清空筛选，目录失败仍可独立核验目标', async () => {
  const hook = show(); await flushPromises(); hook.result.current.select('REVIEW_OVERDUE'); await flushPromises();
  const pending = deferred<HealthReport>(); vi.mocked(healthReport).mockReturnValueOnce(pending.promise);
  hook.result.current.refresh(); await flushPromises(); const signal = vi.mocked(healthReport).mock.calls.at(-1)![4];
  h.facts = 'directory-unavailable'; expect(hook.result.current.data).toBeUndefined(); await flushPromises();
  expect(signal.aborted).toBe(true); expect(hook.result.current.filter).toBe('ALL'); expect(hook.result.current.data).toBeDefined();
  pending.resolve({ ...healthFixture(), affectedDocumentCount: '999' }); await flushPromises();
  expect(hook.result.current.data!.affectedDocumentCount).toBe('1'); hook.unmount();
});

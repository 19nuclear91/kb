import { nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { waitFor } from '@testing-library/vue';
import { renderHook } from '../test/render';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { deferred } from '../test/fixtures';
import type { AuditDetail, AuditPage } from './auditApi';
const h = reactive({ epoch: 1, facts: 'with-space', permissions: 'audit:read', status: 'ready', phase: 'ready', allowed: true, check: vi.fn(), session: { getSnapshot: () => ({ epoch: h.epoch }) } });
vi.mock('../SessionProvider', () => ({ useSession: () => ({ get session() { return h.session; }, get state() { return { epoch: h.epoch, phase: h.phase }; } }) }));
vi.mock('./access', () => ({ useAdminAccess: () => ({ get status() { return h.status; }, get factsKey() { return h.facts; }, get permissionFactsKey() { return h.permissions; }, has: () => h.allowed, check: (...args: unknown[]) => h.check(...args) }) }));
vi.mock('./auditApi', async importOriginal => ({ ...await importOriginal<typeof import('./auditApi')>(), auditPage: vi.fn(), auditDetail: vi.fn() }));
import { auditDetail, auditPage } from './auditApi';
import { useAuditQuery } from './auditQuery';
const firstId = '12345678-1234-1234-1234-123456789012', secondId = '22345678-1234-1234-1234-123456789012';
const window = { from: '2026-10-17T00:00:00.123456Z', to: '2026-10-24T00:00:00.123456Z' };
const row = (eventId = firstId) => ({ id: eventId === firstId ? '9' : '8', eventId, operationScope: 'USER_ENABLE', requestId: 'req', actorUserId: null, targetType: 'user', targetId: '2', operation: 'enable', status: 'SUCCESS', createdAt: '2026-10-20T00:00:00Z', attemptedUsername: null });
const page = (more = true, eventId = firstId): AuditPage => ({ ...window, items: [row(eventId)], hasMore: more, nextCursor: more ? 'cursor_A' : null });
const detail = (eventId = firstId): AuditDetail => ({ metadata: row(eventId), changes: [], summaryAvailable: true });
const loaded = async (result: { current: ReturnType<typeof useAuditQuery> }) => waitFor(() => expect(result.current.items).toHaveLength(1));
beforeEach(() => {
  h.epoch++; h.facts = 'with-space'; h.permissions = 'audit:read'; h.status = 'ready'; h.phase = 'ready'; h.allowed = true; h.check.mockReset();
  vi.mocked(auditPage).mockReset().mockResolvedValue(page()); vi.mocked(auditDetail).mockReset().mockResolvedValue(detail());
});
describe('固定窗口与请求代次单元', () => {
  it('续页沿用微秒窗口，双击只读取一次并保持顺序', async () => {
    const pending = deferred<AuditPage>(), { result } = renderHook(useAuditQuery); await loaded(result); vi.mocked(auditPage).mockReturnValueOnce(pending.promise);
    result.current.more(); result.current.more(); expect(auditPage).toHaveBeenCalledTimes(2); expect(auditPage).toHaveBeenLastCalledWith(h.session, { limit: 20, ...window }, 'cursor_A', expect.any(AbortSignal)); pending.resolve(page(false, secondId)); await flushPromises(); expect(result.current.items.map(item => item.eventId)).toEqual([firstId, secondId]); expect(result.current.hasMore).toBe(false);
  });
  it('默认刷新重新定界，自定义刷新保留条件；无效提交不清空旧页', async () => {
    const { result } = renderHook(useAuditQuery); await loaded(result); result.current.refresh(); await loaded(result); expect(vi.mocked(auditPage).mock.calls.at(-1)?.[1]).toEqual({ limit: 20 }); result.current.submit({ limit: 50, ...window, requestId: 'committed' }); await loaded(result); result.current.refresh(); await loaded(result);
    expect(vi.mocked(auditPage).mock.calls.at(-1)?.[1]).toEqual({ limit: 50, ...window, requestId: 'committed' }); expect(() => result.current.submit({ limit: 20, targetId: '1' })).toThrow(ApiError); expect(result.current.items).toHaveLength(1); result.current.reset(); await loaded(result); expect(result.current.filters).toEqual({ limit: 20 });
  });
  it('提交新条件关闭详情并中止旧列表；旧响应不会覆盖', async () => {
    const pending = deferred<AuditPage>(); vi.mocked(auditPage).mockReturnValueOnce(pending.promise); const { result } = renderHook(useAuditQuery), oldSignal = vi.mocked(auditPage).mock.calls[0][3]; result.current.submit({ limit: 20, operationScope: 'new' }); await loaded(result); expect(oldSignal.aborted).toBe(true); pending.resolve(page(false, secondId)); await flushPromises(); expect(result.current.items[0].eventId).toBe(firstId); expect(result.current.filters.operationScope).toBe('new');
  });
  it('续页暂时失败保留当前页与游标，重试只追加一次', async () => {
    const { result } = renderHook(useAuditQuery); await loaded(result); vi.mocked(auditPage).mockRejectedValueOnce(new ApiError(503)); result.current.more(); await waitFor(() => expect(result.current.status).toBe(503)); expect(result.current.items).toHaveLength(1); vi.mocked(auditPage).mockResolvedValueOnce(page(false, secondId)); result.current.retry(); await waitFor(() => expect(result.current.items).toHaveLength(2)); expect(result.current.error).toBeUndefined();
  });
  it.each(['duplicate', 'window', 'cursor'])('异常页不追加：%s', async kind => {
    const { result } = renderHook(useAuditQuery); await loaded(result); const next = page(false, secondId);
    if (kind === 'duplicate') {
      next.items = [row()];
    }
    if (kind === 'window') {
      next.to = '2026-10-24T00:00:00.123457Z';
    }
    if (kind === 'cursor') {
      next.hasMore = true; next.nextCursor = 'cursor_A';
    }
    vi.mocked(auditPage).mockResolvedValueOnce(next); result.current.more(); await waitFor(() => expect(result.current.status).toBe(503)); expect(result.current.items).toHaveLength(1);
  });
  it('400 停止旧游标，显式重试重新开始查询', async () => {
    const { result } = renderHook(useAuditQuery); await loaded(result); vi.mocked(auditPage).mockRejectedValueOnce(new ApiError(400)); result.current.more(); await waitFor(() => expect(result.current.status).toBe(400)); result.current.more(); expect(auditPage).toHaveBeenCalledTimes(2); result.current.retry(); await loaded(result); expect(vi.mocked(auditPage).mock.calls.at(-1)?.[2]).toBeNull();
  });
  it('首次失败不是空结果，保留错误直到手动重试', async () => {
    vi.mocked(auditPage).mockRejectedValueOnce(new ApiError(503)); const { result } = renderHook(useAuditQuery); await waitFor(() => expect(result.current.status).toBe(503)); expect(result.current.error).toBeTruthy(); expect(auditPage).toHaveBeenCalledTimes(1); result.current.retry(); await loaded(result);
  });
});
describe('详情、资格与隔离单元', () => {
  it('关闭和切换事件隔离迟到详情；刷新取消详情', async () => {
    vi.mocked(auditPage).mockResolvedValue({ ...page(false), items: [row(), row(secondId)] }); const { result } = renderHook(useAuditQuery); await waitFor(() => expect(result.current.items).toHaveLength(2)); const pending = deferred<AuditDetail>(); vi.mocked(auditDetail).mockReturnValueOnce(pending.promise); result.current.open(firstId); const old = vi.mocked(auditDetail).mock.calls[0][2]; vi.mocked(auditDetail).mockResolvedValueOnce(detail(secondId)); result.current.open(secondId); await waitFor(() => expect(result.current.detail?.data?.metadata.eventId).toBe(secondId));
    expect(old.aborted).toBe(true); pending.resolve(detail()); await flushPromises(); expect(result.current.detail?.eventId).toBe(secondId); result.current.close(); expect(result.current.detail).toBeNull(); result.current.open(firstId); result.current.refresh(); expect(result.current.detail).toBeNull();
  });
  it('详情 404 保留列表，身份不匹配视为故障', async () => {
    const { result } = renderHook(useAuditQuery); await loaded(result); vi.mocked(auditDetail).mockRejectedValueOnce(new ApiError(404)); result.current.open(firstId); await waitFor(() => expect(result.current.detail?.status).toBe(404)); expect(result.current.items).toHaveLength(1); vi.mocked(auditDetail).mockResolvedValueOnce(detail(secondId)); result.current.open(firstId); await waitFor(() => expect(result.current.detail?.status).toBe(503)); expect(result.current.detail?.data).toBeUndefined();
  });
  it.each(['list', 'detail'])('403 清理整页，跨 Hook 重挂载只核对一次：%s', async origin => {
    const first = renderHook(useAuditQuery); await loaded(first.result); first.result.current.submit({ limit: 20, requestId: 'private' }); await loaded(first.result);
    if (origin === 'list') {
      vi.mocked(auditPage).mockRejectedValueOnce(new ApiError(403)); first.result.current.more();
    } else {
      vi.mocked(auditDetail).mockRejectedValueOnce(new ApiError(403)); first.result.current.open(firstId);
    }
    await waitFor(() => expect(first.result.current.forbidden).toBe(true)); expect(first.result.current.items).toEqual([]); expect(first.result.current.filters).toEqual({ limit: 20 }); expect(first.result.current.detail).toBeNull(); expect(h.check).toHaveBeenCalledTimes(1);
    const reads = vi.mocked(auditPage).mock.calls.length; first.unmount(); const second = renderHook(useAuditQuery); expect(second.result.current.forbidden).toBe(true); expect(auditPage).toHaveBeenCalledTimes(reads); expect(h.check).toHaveBeenCalledTimes(1); second.result.current.retry(); await loaded(second.result); expect(second.result.current.forbidden).toBe(false);
  });
  it('权限事实改变可以恢复；后台、身份或资格变化取消并清理旧资料', async () => {
    const { result, rerender } = renderHook(useAuditQuery); await loaded(result); const pending = deferred<AuditDetail>(); vi.mocked(auditDetail).mockReturnValueOnce(pending.promise); result.current.open(firstId); h.phase = 'loading'; await rerender(); expect(result.current.items).toEqual([]); expect(result.current.detail).toBeNull(); pending.resolve(detail()); await flushPromises(); expect(result.current.detail).toBeNull(); h.epoch++; h.phase = 'ready'; await rerender(); await loaded(result); expect(result.current.filters).toEqual({ limit: 20 }); h.allowed = false; await rerender(); expect(result.current.items).toEqual([]); h.allowed = true; h.permissions = 'new'; await rerender(); await loaded(result);
  });
  it('详情拒绝同时取消在途续页，晚到页不能恢复资料', async () => {
    const pending = deferred<AuditPage>(), { result } = renderHook(useAuditQuery); await loaded(result); vi.mocked(auditPage).mockReturnValueOnce(pending.promise); result.current.more(); const signal = vi.mocked(auditPage).mock.calls.at(-1)![3]; vi.mocked(auditDetail).mockRejectedValueOnce(new ApiError(403)); result.current.open(firstId); await waitFor(() => expect(result.current.forbidden).toBe(true)); expect(signal.aborted).toBe(true); pending.resolve(page(false, secondId)); await flushPromises(); expect(result.current.items).toEqual([]);
  });
  it('权限事实变更解除旧拒绝，事实再次变化不复用过期拒绝', async () => {
    vi.mocked(auditPage).mockRejectedValueOnce(new ApiError(403)); const { result, rerender } = renderHook(useAuditQuery); await waitFor(() => expect(result.current.forbidden).toBe(true)); h.permissions = 'audit:read,new'; await rerender(); await loaded(result); h.permissions = 'audit:read'; await rerender(); await loaded(result); expect(result.current.forbidden).toBe(false);
  });
  it('401 清理；卸载后响应不会重新写入', async () => {
    const pending = deferred<AuditPage>(); const { result, unmount } = renderHook(useAuditQuery); await loaded(result); vi.mocked(auditPage).mockRejectedValueOnce(new ApiError(401)); result.current.more(); await waitFor(() => expect(result.current.status).toBe(401)); expect(result.current.items).toEqual([]); vi.mocked(auditPage).mockReturnValueOnce(pending.promise); result.current.refresh(); const last = vi.mocked(auditPage).mock.calls.at(-1)![3]; unmount(); expect(last.aborted).toBe(true); pending.resolve(page()); await flushPromises();
  });
  it.each(['list', 'detail'])('慢资格核对未结束时已清理结果、窗口和查询：%s', async origin => {
    const verifying = deferred<void>(), { result } = renderHook(useAuditQuery); await loaded(result); result.current.submit({ limit: 20, requestId: 'private' }); await loaded(result); h.check.mockReturnValue(verifying.promise);
    if (origin === 'list') {
      vi.mocked(auditPage).mockRejectedValueOnce(new ApiError(403)); result.current.more();
    } else {
      vi.mocked(auditDetail).mockRejectedValueOnce(new ApiError(403)); result.current.open(firstId);
    }
    try {
      await waitFor(() => expect(result.current.forbidden).toBe(true)); expect(result.current.items).toEqual([]); expect(result.current.window).toBeUndefined(); expect(result.current.detail).toBeNull(); expect(result.current.filters).toEqual({ limit: 20 }); expect(h.check).toHaveBeenCalledTimes(1);
    } finally {
      verifying.resolve(); await flushPromises();
    }
  });
  it.each(['list', 'detail'])('权限先返回、空间后返回不解除拒绝或重启读取：%s', async origin => {
    const first = renderHook(useAuditQuery); await loaded(first.result);
    if (origin === 'list') {
      vi.mocked(auditPage).mockRejectedValueOnce(new ApiError(403)); first.result.current.more();
    } else {
      vi.mocked(auditDetail).mockRejectedValueOnce(new ApiError(403)); first.result.current.open(firstId);
    }
    await waitFor(() => expect(first.result.current.forbidden).toBe(true)); const reads = vi.mocked(auditPage).mock.calls.length; first.unmount(); h.status = 'loading'; h.facts = 'empty'; const second = renderHook(useAuditQuery); h.status = 'ready'; h.facts = 'permissions-with-empty-spaces'; await second.rerender(); expect(second.result.current.forbidden).toBe(true);
    for (const facts of ['with-space', 'directory-failure', 'empty', 'with-space']) {
      h.facts = facts; await second.rerender(); expect(second.result.current.forbidden).toBe(true);
    }
    expect(auditPage).toHaveBeenCalledTimes(reads); expect(h.check).toHaveBeenCalledTimes(1); second.result.current.retry(); await loaded(second.result); expect(auditPage).toHaveBeenCalledTimes(reads + 1);
  });
  it('正常查询不因空间目录变化而丢失筛选或重新读取', async () => {
    const { result, rerender } = renderHook(useAuditQuery); await loaded(result); result.current.submit({ limit: 50, requestId: 'committed' }); await loaded(result); result.current.open(firstId); await waitFor(() => expect(result.current.detail?.data).toBeTruthy()); const reads = vi.mocked(auditPage).mock.calls.length;
    for (const facts of ['empty', 'directory-failure', 'with-space']) {
      h.facts = facts; await rerender();
    }
    expect(auditPage).toHaveBeenCalledTimes(reads); expect(result.current.filters.requestId).toBe('committed'); expect(result.current.detail?.data?.metadata.eventId).toBe(firstId);
  });
});

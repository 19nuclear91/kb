import { beforeEach, expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type { adminWrite } from '../admin/api';
import { useAdminWrite } from '../admin/hooks';
import { FavoriteStore } from '../favorites/store';
import type { Session } from '../session';
import { deferred } from '../test/fixtures';
import { renderHook } from '../test/render';
import type { DocumentOperations } from './operations';

const f = vi.hoisted(() => {
  const state = { pending: 0, exclusive: false, uncertain: 0, uncertaintyRevision: 0 };
  const settle = vi.fn(), beginWrite = vi.fn(), markUncertain = vi.fn();
  return { state, settle, beginWrite, markUncertain, enabled: true,
    operations: { getSnapshot: () => state, beginWrite, markUncertain } };
});
vi.mock('../SessionProvider', () => ({ useSession: () => ({
  session: { getSnapshot: () => ({ epoch: 1 }) }, state: { epoch: 1 },
}) }));
vi.mock('../admin/access', () => ({ useAdminAccess: () => ({ check: () => {} }) }));
vi.mock('./OperationContext', () => ({ useDocumentOperations: () => ({
  operations: f.enabled ? f.operations : null, state: f.enabled ? f.state : { exclusive: false },
}) }));

beforeEach(() => {
  f.enabled = true; f.state.exclusive = false; f.settle.mockReset(); f.markUncertain.mockReset();
  f.beginWrite.mockReset().mockImplementation(() => f.state.exclusive ? null : { settle: f.settle });
});
const intent = { method: 'POST' as const, path: '/api/documents/1/versions/2/review/submit', body: { expectedStateVersion: 1 } };

it('审核/评论共用写 hook 在提交前登记，删除独占时阻止新写入和原绑定重试', async () => {
  const execute = vi.fn().mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce({});
  const hook = renderHook(() => useAdminWrite('comment:1/2/root', execute as typeof adminWrite));
  await hook.result.current.submit(intent);
  expect(f.beginWrite).toHaveBeenCalledWith('1|comment:1/2/root'); expect(f.settle).toHaveBeenCalledWith('uncertain');
  const requestId = execute.mock.calls[0][2];
  f.state.exclusive = true; await hook.rerender();
  await hook.result.current.submit(intent); await hook.result.current.retry();
  expect(execute).toHaveBeenCalledOnce(); expect(hook.result.current.blocked).toBe(true);
  f.state.exclusive = false; await hook.rerender();
  await hook.result.current.retry();
  expect(execute.mock.calls[1][2]).toBe(requestId); expect(f.settle).toHaveBeenLastCalledWith('confirmed');
});

it('写 hook 卸载后仍等待请求结果，abort 报告为未确认而非取消成功', async () => {
  const pending = deferred<unknown>(), execute = vi.fn(() => pending.promise);
  const hook = renderHook(() => useAdminWrite('review:1/2', execute as typeof adminWrite));
  let submitted!: ReturnType<typeof hook.result.current.submit>;
  submitted = hook.result.current.submit(intent); hook.unmount();
  expect(f.settle).not.toHaveBeenCalled();
  pending.reject(new DOMException('aborted', 'AbortError')); await submitted;
  expect(f.settle).toHaveBeenCalledExactlyOnceWith('uncertain');
});

it('无详情上下文时共用写 hook 保留既有执行语义', async () => {
  f.enabled = false; f.state.exclusive = true;
  const execute = vi.fn().mockResolvedValue({});
  const hook = renderHook(() => useAdminWrite('other-page', execute as typeof adminWrite));
  await hook.result.current.submit(intent);
  expect(execute).toHaveBeenCalledOnce(); expect(f.beginWrite).not.toHaveBeenCalled(); expect(hook.result.current.blocked).toBe(false);
});

it('收藏写入登记与独占门禁覆盖实际发送，页面销毁不伪报取消成功', async () => {
  const pending = deferred<unknown>(), json = vi.fn(() => pending.promise);
  const store = new FavoriteStore({ json } as unknown as Session, f.operations as unknown as DocumentOperations);
  store.bind('page', ['1'], true); f.state.exclusive = true;
  await store.set('1', false); expect(json).not.toHaveBeenCalled();
  f.state.exclusive = false; const submitted = store.set('1', false); store.dispose();
  expect(f.settle).not.toHaveBeenCalled();
  pending.reject(new DOMException('aborted', 'AbortError')); await submitted;
  expect(f.settle).toHaveBeenCalledExactlyOnceWith('uncertain'); expect(store.getSnapshot()).toEqual({}); expect(store.source).toBeUndefined();
});

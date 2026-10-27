import { describe, expect, it, vi } from 'vitest';
import { act, render, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { SessionProvider, useSession } from '../SessionProvider';
import { Session } from '../session';
import { deferred, json, me, storage } from '../test/fixtures';
import { emptyAdminAccess, AdminAccessContext } from './access';
import { adminWrites, type WriteIntent } from './api';
import { spaceWrites } from './spaceApi';
import { useAdminResource, useAdminWrite, type AdminWrite, type WriteOutcome } from './hooks';

type Call = { path: string; init: RequestInit; reply: ReturnType<typeof deferred<Response>> };
const failure = (status: number, reasonCode: string) => new Response(JSON.stringify({ code: 500, message: '原文', data: null, reasonCode }), { status, headers: { 'Content-Type': 'application/json' } });
const offline = (call: Call) => call.reply.reject(new TypeError('offline'));
const idOf = (call: Call) => new Headers(call.init.headers).get('X-Request-Id');
/** 真实 Session + 受控 transport：除身份接口外的每个请求都挂起，由测试决定何时、以何种结果返回。 */
function harness({ honorAbort = true } = {}) {
  const calls: Call[] = [], check = vi.fn();
  const transport = vi.fn((input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input);
    if (path === '/api/auth/me') return Promise.resolve(json(me));
    if (path === '/api/auth/logout') return Promise.resolve(json(null));
    if (path === '/api/auth/login') return Promise.resolve(json({ tokenName: 'satoken', tokenValue: 'second-token', mustChangePassword: false, user: me.user }));
    const reply = deferred<Response>(); calls.push({ path, init, reply });
    if (honorAbort) init.signal?.addEventListener('abort', () => reply.reject(new DOMException('aborted', 'AbortError')));
    return reply.promise;
  });
  const session = new Session(storage(), transport as typeof fetch);
  const wrapper = ({ children }: { children: ReactNode }) => <SessionProvider session={session}>
    <AdminAccessContext value={{ ...emptyAdminAccess, status: 'ready', checking: false, has: () => true, check }}>{children}</AdminAccessContext></SessionProvider>;
  const ready = () => waitFor(() => expect(session.getSnapshot().phase).toBe('ready'));
  return { session, calls, check, wrapper, ready };
}
function start(write: () => AdminWrite, intent: WriteIntent) { let promise!: Promise<WriteOutcome<unknown>>; act(() => { promise = write().submit(intent); }); return promise; }

describe('共用写操作状态与请求绑定', () => {
  it('空间搬迁复用状态机，原意图重试且目标变化后生成新标识', async () => {
    const h = harness(), { result } = renderHook(() => useAdminWrite('document-space:7'), { wrapper: h.wrapper });
    await h.ready();
    const first = start(() => result.current, spaceWrites.relocate('7', '2', '3'));
    const original = idOf(h.calls[0]);
    await act(async () => { offline(h.calls[0]); await first; });
    expect(h.calls).toHaveLength(1);
    let retry!: Promise<WriteOutcome<unknown>>;
    act(() => { retry = result.current.retry(); });
    expect(idOf(h.calls[1])).toBe(original); expect(h.calls[1].init.body).toBe(h.calls[0].init.body);
    await act(async () => { offline(h.calls[1]); await retry; });
    const changed = start(() => result.current, spaceWrites.relocate('7', '2', '4'));
    expect(idOf(h.calls[2])).not.toBe(original);
    await act(async () => { h.calls[2].reply.resolve(json(null)); await changed; });
  });
  it('双击只提交一次；结果不确定时同内容重试复用标识，且不自动重传', async () => {
    const h = harness(), { result } = renderHook(() => useAdminWrite('user-enabled:5'), { wrapper: h.wrapper });
    await h.ready();
    const intent = adminWrites.setUserEnabled('5', false, 3);
    let first!: Promise<WriteOutcome<unknown>>, second!: Promise<WriteOutcome<unknown>>;
    act(() => { first = result.current.submit(intent); second = result.current.submit(() => intent); });
    await expect(second).resolves.toEqual({ status: 'ignored' });
    expect(h.calls).toHaveLength(1); expect(result.current).toMatchObject({ phase: 'submitting', busy: true });
    const id = idOf(h.calls[0]);
    await act(async () => { offline(h.calls[0]); await first; });
    expect(result.current).toMatchObject({ phase: 'uncertain', pending: true });
    await act(() => new Promise(resolve => setTimeout(resolve, 30)));
    expect(h.calls).toHaveLength(1);
    let retried!: Promise<WriteOutcome<unknown>>;
    act(() => { retried = result.current.retry(); });
    expect(idOf(h.calls[1])).toBe(id); expect(h.calls[1].init.body).toBe(h.calls[0].init.body); expect(h.calls[1].init.method).toBe('PATCH');
    await act(async () => { offline(h.calls[1]); await retried; });
    const again = start(() => result.current, adminWrites.setUserEnabled('5', false, 3));
    expect(idOf(h.calls[2])).toBe(id);
    await act(async () => { h.calls[2].reply.resolve(json({ userId: 5 })); await again; });
    expect(result.current.phase).toBe('confirmed');
    await act(async () => { await expect(result.current.retry()).resolves.toEqual({ status: 'ignored' }); });
    start(() => result.current, intent);
    expect(idOf(h.calls[3])).not.toBe(id);
  });
  it('内容、版本或目标改变即新标识；版本冲突明确拒绝后重新确认也使用新标识', async () => {
    const h = harness(), { result, rerender } = renderHook(({ scope }) => useAdminWrite(scope), { wrapper: h.wrapper, initialProps: { scope: 'user-enabled:5' } });
    await h.ready();
    const ids: (string | null)[] = [];
    const attempt = async (intent: WriteIntent, settle: (call: Call) => void) => {
      const promise = start(() => result.current, intent), call = h.calls.at(-1)!; ids.push(idOf(call));
      await act(async () => { settle(call); await promise; });
    };
    await attempt(adminWrites.setUserEnabled('5', false, 3), offline);
    await attempt(adminWrites.setUserEnabled('5', false, 4), offline);
    await attempt(adminWrites.setUserEnabled('5', true, 4), offline);
    await attempt(adminWrites.setUserEnabled('6', true, 4), offline);
    expect(new Set(ids).size).toBe(4);
    await attempt(adminWrites.setUserEnabled('6', true, 4), call => call.reply.resolve(failure(409, 'INVALID_VERSION')));
    expect(ids[4]).toBe(ids[3]);
    expect(result.current).toMatchObject({ phase: 'rejected', reason: 'INVALID_VERSION', status: 409, message: '数据已被其他操作更新，请重新读取后确认新的版本。' });
    await attempt(adminWrites.setUserEnabled('6', true, 5), offline);
    expect(ids[5]).not.toBe(ids[4]);
    rerender({ scope: 'user-enabled:6' });
    expect(result.current.phase).toBe('idle');
    await attempt(adminWrites.setUserEnabled('6', true, 5), offline);
    expect(ids[6]).not.toBe(ids[5]); expect(h.calls).toHaveLength(7);
  });
  it('构造失败在发送前拒绝；403 拒绝触发一次本人权限核对', async () => {
    const h = harness(), { result } = renderHook(() => useAdminWrite('x'), { wrapper: h.wrapper });
    await h.ready();
    await act(async () => { await expect(result.current.submit(() => adminWrites.setUserEnabled('5', true, -1))).resolves.toMatchObject({ status: 'rejected' }); });
    expect(h.calls).toHaveLength(0); expect(result.current.phase).toBe('rejected');
    const promise = start(() => result.current, adminWrites.deleteRole('3'));
    await act(async () => { h.calls[0].reply.resolve(failure(403, 'FORBIDDEN')); await promise; });
    expect(result.current).toMatchObject({ phase: 'rejected', reason: 'FORBIDDEN' }); expect(h.check).toHaveBeenCalledExactlyOnceWith(true);
  });
});

describe('与会话 epoch、目标和表单生命周期关联', () => {
  it('退出后晚到的成功不更新界面，含密码的待提交绑定被丢弃', async () => {
    const h = harness({ honorAbort: false }), { result } = renderHook(() => useAdminWrite('user-password:5'), { wrapper: h.wrapper });
    await h.ready();
    const promise = start(() => result.current, adminWrites.resetPassword('5', 'Secret#1'));
    await act(async () => { await h.session.logout(); });
    expect(h.calls[0].init.signal?.aborted).toBe(true); expect(result.current.phase).toBe('idle');
    await act(async () => { h.calls[0].reply.resolve(json({ userId: 5 })); await expect(promise).resolves.toEqual({ status: 'ignored' }); });
    expect(result.current.phase).toBe('idle');
    await act(async () => { await expect(result.current.retry()).resolves.toEqual({ status: 'ignored' }); });
    expect(h.calls).toHaveLength(1);
  });
  it('换号后旧结果被隔离，同内容在新身份下使用新标识', async () => {
    const h = harness({ honorAbort: false }), { result } = renderHook(() => useAdminWrite('role-name:3'), { wrapper: h.wrapper });
    await h.ready();
    const intent = adminWrites.renameRole('3', '新名'), promise = start(() => result.current, intent);
    await act(async () => { offline(h.calls[0]); await promise; });
    expect(result.current.phase).toBe('uncertain');
    await act(async () => { await h.session.login('carol', 'pw'); });
    expect(result.current.phase).toBe('idle');
    start(() => result.current, intent);
    expect(idOf(h.calls[1])).not.toBe(idOf(h.calls[0])); expect(new Headers(h.calls[1].init.headers).get('satoken')).toBe('second-token');
  });
  it('关闭窗口卸载表单即中止请求，晚到回执不再生效', async () => {
    const h = harness({ honorAbort: false }); let write!: AdminWrite;
    function Dialog() { write = useAdminWrite('department-name:4'); return <output>{write.phase}</output>; }
    const view = render(<h.wrapper><Dialog /></h.wrapper>);
    await h.ready();
    const promise = start(() => write, adminWrites.renameDepartment('4', '新名', 2));
    view.rerender(<h.wrapper>{null}</h.wrapper>);
    expect(h.calls[0].init.signal?.aborted).toBe(true); expect(h.session.getSnapshot().phase).toBe('ready');
    h.calls[0].reply.resolve(json({ departmentId: 4 }));
    await expect(promise).resolves.toEqual({ status: 'ignored' });
  });
  it('切换目标后旧目标的回执不会更新新目标', async () => {
    const h = harness({ honorAbort: false }), { result, rerender } = renderHook(({ scope }) => useAdminWrite(scope), { wrapper: h.wrapper, initialProps: { scope: 'user-clearance:5' } });
    await h.ready();
    const promise = start(() => result.current, adminWrites.changeClearance('5', 2, 1));
    rerender({ scope: 'user-clearance:6' });
    expect(h.calls[0].init.signal?.aborted).toBe(true); expect(result.current.phase).toBe('idle');
    await act(async () => { h.calls[0].reply.resolve(json({ userId: 5 })); await expect(promise).resolves.toEqual({ status: 'ignored' }); });
    expect(result.current.phase).toBe('idle');
    start(() => result.current, adminWrites.changeClearance('6', 2, 1));
    expect(result.current.phase).toBe('submitting'); expect(h.calls).toHaveLength(2);
  });
  it('响应体读取途中身份变化：已读到的回执按中止丢弃', async () => {
    const h = harness(), { result } = renderHook(() => useAdminWrite('role-name:3'), { wrapper: h.wrapper });
    await h.ready();
    const promise = start(() => result.current, adminWrites.renameRole('3', '新名'));
    let body!: ReadableStreamDefaultController<Uint8Array>;
    const stream = new ReadableStream<Uint8Array>({ start: controller => { body = controller; } });
    await act(async () => { h.calls[0].reply.resolve(new Response(stream, { status: 200, headers: { 'Content-Type': 'application/json' } })); });
    body.enqueue(new TextEncoder().encode('{"code":200,'));
    await act(() => new Promise(resolve => setTimeout(resolve, 20)));
    expect(result.current.phase).toBe('submitting');
    act(() => h.session.suspend());
    try { body.enqueue(new TextEncoder().encode('"data":{"roleId":3,"code":"R","name":"新名","permissions":[]}}')); body.close(); } catch { /* 读取端已取消 */ }
    await expect(promise).resolves.toEqual({ status: 'ignored' });
    expect(result.current.phase).toBe('idle');
  });
  it('提交成功但刷新失败：写入保持已确认，只重试读取', async () => {
    const h = harness();
    const { result } = renderHook(() => { const { session } = useSession(); return { write: useAdminWrite('user-enabled:5'),
      detail: useAdminResource(signal => session.json<{ version: number }>('/api/admin/identity/users/5', { signal }), '5') }; }, { wrapper: h.wrapper });
    await h.ready();
    // 挂载时身份尚在确认，首个读取随 epoch 变化被中止，并在新 epoch 下自动重新读取。
    await waitFor(() => expect(h.calls.at(-1)?.init.signal?.aborted).toBe(false));
    const base = h.calls.length - 1;
    expect(h.calls.slice(0, base).every(call => call.init.signal?.aborted)).toBe(true);
    await act(async () => { h.calls[base].reply.resolve(json({ userId: 5, version: 3 })); });
    await waitFor(() => expect(result.current.detail.data).toEqual({ userId: '5', version: 3 }));
    const promise = start(() => result.current.write, adminWrites.setUserEnabled('5', false, result.current.detail.data!.version));
    await act(async () => { h.calls[base + 1].reply.resolve(json({ userId: 5, version: 4 })); await promise; });
    act(() => result.current.detail.reload());
    await waitFor(() => expect(h.calls).toHaveLength(base + 3));
    await act(async () => { h.calls[base + 2].reply.resolve(new Response(null, { status: 503 })); });
    await waitFor(() => expect(result.current.detail.error).toBe('服务暂不可用，请稍后重试。'));
    expect(result.current.write.phase).toBe('confirmed');
    act(() => result.current.detail.reload());
    await waitFor(() => expect(h.calls).toHaveLength(base + 4));
    expect(h.calls.slice(base).map(call => call.init.method ?? 'GET')).toEqual(['GET', 'PATCH', 'GET', 'GET']);
  });
});

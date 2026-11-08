import { describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, ref, type FunctionalComponent } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { render, waitFor } from '@testing-library/vue';
import { createPinia, setActivePinia } from 'pinia';
import SessionHost from '../SessionHost.vue';
import { useSession } from '../SessionProvider';
import { Session } from '../session';
import { deferred, json, me, storage } from '../test/fixtures';
import { renderHook } from '../test/render';
import { emptyAdminAccess, type AdminAccess } from '../stores/adminAccess';
import { adminWrites, type WriteIntent } from './api';
import { spaceWrites } from './spaceApi';
import { inFlight, useAdminResource, useAdminWrite, type AdminWrite, type WriteBindings, type WriteOutcome } from './hooks';

const fake = vi.hoisted(() => ({ access: null as unknown }));
vi.mock('./access', () => ({ useAdminAccess: () => fake.access }));

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
  fake.access = { ...emptyAdminAccess, status: 'ready', checking: false, has: () => true, check } satisfies AdminAccess;
  const wrapper: FunctionalComponent = (_, { slots }) => h(SessionHost, { session }, slots);
  const ready = () => waitFor(() => expect(session.getSnapshot().phase).toBe('ready'));
  return { session, calls, check, wrapper, ready };
}
/** 对应 React 版 rerender(<wrapper>{null}</wrapper>)：在保持会话宿主的同时卸载、重新挂载表单。 */
function mountForm(harnessed: ReturnType<typeof harness>, use: () => AdminWrite) {
  setActivePinia(createPinia());
  const shown = ref(true); const holder = { write: undefined as unknown as AdminWrite };
  const Form = defineComponent({ setup() { holder.write = use(); return () => h('output', holder.write.phase); } });
  const Root = defineComponent({ setup: () => () => h(harnessed.wrapper, null, { default: () => shown.value ? h(Form) : null }) });
  render(Root);
  return { holder, async toggle(value: boolean) { shown.value = value; await nextTick(); } };
}
function start(write: () => AdminWrite, intent: WriteIntent) { return write().submit(intent); }

describe('共用写操作状态与请求绑定', () => {
  it('空间搬迁复用状态机，原意图重试且目标变化后生成新标识', async () => {
    const h = harness(), { result } = renderHook(() => useAdminWrite('document-space:7'), { wrapper: h.wrapper });
    await h.ready();
    const first = start(() => result.current, spaceWrites.relocate('7', '2', '3', null, 0));
    const original = idOf(h.calls[0]);
    offline(h.calls[0]); await first;
    expect(h.calls).toHaveLength(1);
    const retry = result.current.retry();
    expect(idOf(h.calls[1])).toBe(original); expect(h.calls[1].init.body).toBe(h.calls[0].init.body);
    offline(h.calls[1]); await retry;
    const changed = start(() => result.current, spaceWrites.relocate('7', '2', '4', null, 0));
    expect(idOf(h.calls[2])).not.toBe(original);
    h.calls[2].reply.resolve(json(null)); await changed;
  });
  it('双击只提交一次；结果不确定时同内容重试复用标识，且不自动重传', async () => {
    const h = harness(), { result } = renderHook(() => useAdminWrite('user-enabled:5'), { wrapper: h.wrapper });
    await h.ready();
    const intent = adminWrites.setUserEnabled('5', false, 3);
    const first = result.current.submit(intent), second = result.current.submit(() => intent);
    await expect(second).resolves.toEqual({ status: 'ignored' });
    expect(h.calls).toHaveLength(1); expect(result.current).toMatchObject({ phase: 'submitting', busy: true });
    const id = idOf(h.calls[0]);
    offline(h.calls[0]); await first;
    expect(result.current).toMatchObject({ phase: 'uncertain', pending: true });
    await new Promise(resolve => setTimeout(resolve, 30));
    expect(h.calls).toHaveLength(1);
    const retried = result.current.retry();
    expect(idOf(h.calls[1])).toBe(id); expect(h.calls[1].init.body).toBe(h.calls[0].init.body); expect(h.calls[1].init.method).toBe('PATCH');
    offline(h.calls[1]); await retried;
    const again = start(() => result.current, adminWrites.setUserEnabled('5', false, 3));
    expect(idOf(h.calls[2])).toBe(id);
    h.calls[2].reply.resolve(json({ userId: 5 })); await again;
    expect(result.current.phase).toBe('confirmed');
    await expect(result.current.retry()).resolves.toEqual({ status: 'ignored' });
    start(() => result.current, intent);
    expect(idOf(h.calls[3])).not.toBe(id);
  });
  it('内容、版本或目标改变即新标识；版本冲突明确拒绝后重新确认也使用新标识', async () => {
    const h = harness(), { result, rerender } = renderHook((props: { scope: string }) => useAdminWrite(() => props.scope), { wrapper: h.wrapper, initialProps: { scope: 'user-enabled:5' } });
    await h.ready();
    const ids: (string | null)[] = [];
    const attempt = async (intent: WriteIntent, settle: (call: Call) => void) => {
      const promise = start(() => result.current, intent), call = h.calls.at(-1)!; ids.push(idOf(call));
      settle(call); await promise;
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
    await rerender({ scope: 'user-enabled:6' });
    expect(result.current.phase).toBe('idle');
    await attempt(adminWrites.setUserEnabled('6', true, 5), offline);
    expect(ids[6]).not.toBe(ids[5]); expect(h.calls).toHaveLength(7);
  });
  it('构造失败在发送前拒绝；403 拒绝触发一次本人权限核对', async () => {
    const h = harness(), { result } = renderHook(() => useAdminWrite('x'), { wrapper: h.wrapper });
    await h.ready();
    await expect(result.current.submit(() => adminWrites.setUserEnabled('5', true, -1))).resolves.toMatchObject({ status: 'rejected' });
    expect(h.calls).toHaveLength(0); expect(result.current.phase).toBe('rejected');
    const promise = start(() => result.current, adminWrites.deleteRole('3'));
    h.calls[0].reply.resolve(failure(403, 'FORBIDDEN')); await promise;
    expect(result.current).toMatchObject({ phase: 'rejected', reason: 'FORBIDDEN' }); expect(h.check).toHaveBeenCalledExactlyOnceWith(true);
  });
});

describe('与会话 epoch、目标和表单生命周期关联', () => {
  it('退出后晚到的成功不更新界面，含密码的待提交绑定被丢弃', async () => {
    const h = harness({ honorAbort: false }), { result } = renderHook(() => useAdminWrite('user-password:5'), { wrapper: h.wrapper });
    await h.ready();
    const promise = start(() => result.current, adminWrites.resetPassword('5', 'Secret#1'));
    await h.session.logout(); await nextTick();
    expect(h.calls[0].init.signal?.aborted).toBe(true); expect(result.current.phase).toBe('idle');
    h.calls[0].reply.resolve(json({ userId: 5 })); await expect(promise).resolves.toEqual({ status: 'ignored' });
    expect(result.current.phase).toBe('idle');
    await expect(result.current.retry()).resolves.toEqual({ status: 'ignored' });
    expect(h.calls).toHaveLength(1);
  });
  it('换号后旧结果被隔离，同内容在新身份下使用新标识', async () => {
    const h = harness({ honorAbort: false }), { result } = renderHook(() => useAdminWrite('role-name:3'), { wrapper: h.wrapper });
    await h.ready();
    const intent = adminWrites.renameRole('3', '新名'), promise = start(() => result.current, intent);
    offline(h.calls[0]); await promise;
    expect(result.current.phase).toBe('uncertain');
    await h.session.login('carol', 'pw'); await nextTick();
    expect(result.current.phase).toBe('idle');
    start(() => result.current, intent);
    expect(idOf(h.calls[1])).not.toBe(idOf(h.calls[0])); expect(new Headers(h.calls[1].init.headers).get('satoken')).toBe('second-token');
  });
  it('关闭窗口卸载表单即中止请求，晚到回执不再生效', async () => {
    const h = harness({ honorAbort: false });
    const form = mountForm(h, () => useAdminWrite('department-name:4'));
    await h.ready();
    const promise = start(() => form.holder.write, adminWrites.renameDepartment('4', '新名', 2));
    await form.toggle(false);
    expect(h.calls[0].init.signal?.aborted).toBe(true); expect(h.session.getSnapshot().phase).toBe('ready');
    h.calls[0].reply.resolve(json({ departmentId: 4 }));
    await expect(promise).resolves.toEqual({ status: 'ignored' });
  });
  it('调用方保存绑定时卸载重挂载仍待核对并沿用原标识；调用方清空后同内容使用新标识', async () => {
    const h = harness(), bindings: WriteBindings = new Map();
    const form = mountForm(h, () => useAdminWrite('role-name:3', undefined, bindings));
    await h.ready();
    const intent = adminWrites.renameRole('3', '新名'), first = start(() => form.holder.write, intent);
    offline(h.calls[0]); await first;
    expect(form.holder.write.phase).toBe('uncertain');
    await form.toggle(false);
    await form.toggle(true);
    expect(form.holder.write).toMatchObject({ phase: 'uncertain', pending: true });
    const retried = form.holder.write.retry();
    expect(idOf(h.calls[1])).toBe(idOf(h.calls[0])); expect(h.calls[1].init.body).toBe(h.calls[0].init.body);
    offline(h.calls[1]); await retried;
    await form.toggle(false);
    bindings.clear();
    await form.toggle(true);
    expect(form.holder.write.phase).toBe('idle');
    start(() => form.holder.write, intent);
    expect(idOf(h.calls[2])).not.toBe(idOf(h.calls[0]));
  });
  it('调用方的表跨会话世代存活时，旧世代的未决绑定被删除，新身份下同内容使用新标识', async () => {
    const h = harness(), bindings: WriteBindings = new Map(), { result } = renderHook(() => useAdminWrite('role-name:3', undefined, bindings), { wrapper: h.wrapper });
    await h.ready();
    const intent = adminWrites.renameRole('3', '新名'), promise = start(() => result.current, intent);
    offline(h.calls[0]); await promise;
    expect(result.current.phase).toBe('uncertain'); expect(bindings.size).toBe(1);
    await h.session.login('carol', 'pw'); await nextTick();
    expect(bindings.size).toBe(0); expect(result.current.phase).toBe('idle');
    start(() => result.current, intent);
    expect(idOf(h.calls[1])).not.toBe(idOf(h.calls[0]));
  });
  it('调用方删除条目后待核对视图回到空闲，不再提供无对象的重试', async () => {
    const h = harness(), bindings: WriteBindings = new Map(), { result, rerender } = renderHook(() => useAdminWrite('role-name:3', undefined, bindings), { wrapper: h.wrapper });
    await h.ready();
    const promise = start(() => result.current, adminWrites.renameRole('3', '新名'));
    offline(h.calls[0]); await promise;
    expect(result.current.phase).toBe('uncertain');
    bindings.clear(); await rerender();
    expect(result.current).toMatchObject({ phase: 'idle', pending: false });
    await expect(result.current.retry()).resolves.toEqual({ status: 'ignored' });
    expect(h.calls).toHaveLength(1);
  });
  it('绑定只在请求在途时标记；重挂载后对同一绑定重试，旧请求结束不清除新请求的在途标记', async () => {
    const h = harness({ honorAbort: false }), bindings: WriteBindings = new Map();
    const form = mountForm(h, () => useAdminWrite('role-name:3', undefined, bindings));
    await h.ready();
    const first = start(() => form.holder.write, adminWrites.renameRole('3', '新名'));
    const entry = [...bindings.values()][0];
    expect(inFlight(entry)).toBe(true);
    offline(h.calls[0]); await first;
    expect(form.holder.write.phase).toBe('uncertain'); expect(inFlight(entry)).toBe(false);
    void form.holder.write.retry();
    expect(inFlight(entry)).toBe(true);
    // 旧请求不响应中止，卸载后仍挂起；重挂载后立即对同一绑定再次重试。
    await form.toggle(false);
    await form.toggle(true);
    const latest = form.holder.write.retry();
    expect(idOf(h.calls[2])).toBe(idOf(h.calls[0]));
    offline(h.calls[1]); await flushPromises();
    expect(inFlight(entry)).toBe(true);
    offline(h.calls[2]); await latest;
    expect(inFlight(entry)).toBe(false); expect(form.holder.write.phase).toBe('uncertain');
    expect(bindings.size).toBe(1); expect([...bindings.values()][0]).toBe(entry);
  });
  it('切换目标后旧目标的回执不会更新新目标', async () => {
    const h = harness({ honorAbort: false }), { result, rerender } = renderHook((props: { scope: string }) => useAdminWrite(() => props.scope), { wrapper: h.wrapper, initialProps: { scope: 'user-clearance:5' } });
    await h.ready();
    const promise = start(() => result.current, adminWrites.changeClearance('5', 2, 1));
    await rerender({ scope: 'user-clearance:6' });
    expect(h.calls[0].init.signal?.aborted).toBe(true); expect(result.current.phase).toBe('idle');
    h.calls[0].reply.resolve(json({ userId: 5 })); await expect(promise).resolves.toEqual({ status: 'ignored' });
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
    h.calls[0].reply.resolve(new Response(stream, { status: 200, headers: { 'Content-Type': 'application/json' } })); await flushPromises();
    body.enqueue(new TextEncoder().encode('{"code":200,'));
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(result.current.phase).toBe('submitting');
    h.session.suspend(); await nextTick();
    try { body.enqueue(new TextEncoder().encode('"data":{"roleId":3,"code":"R","name":"新名","permissions":[]}}')); body.close(); } catch { /* 读取端已取消 */ }
    await expect(promise).resolves.toEqual({ status: 'ignored' });
    expect(result.current.phase).toBe('idle');
  });
  it('提交成功但刷新失败：写入保持已确认，只重试读取', async () => {
    const h = harness();
    const { result } = renderHook(() => { const auth = useSession(); return { write: useAdminWrite('user-enabled:5'),
      detail: useAdminResource(signal => auth.session.json<{ version: number }>('/api/admin/identity/users/5', { signal }), '5') }; }, { wrapper: h.wrapper });
    await h.ready();
    // 挂载时身份尚在确认，首个读取随 epoch 变化被中止，并在新 epoch 下自动重新读取。
    await waitFor(() => expect(h.calls.at(-1)?.init.signal?.aborted).toBe(false));
    const base = h.calls.length - 1;
    expect(h.calls.slice(0, base).every(call => call.init.signal?.aborted)).toBe(true);
    h.calls[base].reply.resolve(json({ userId: 5, version: 3 })); await flushPromises();
    await waitFor(() => expect(result.current.detail.data).toEqual({ userId: '5', version: 3 }));
    const promise = start(() => result.current.write, adminWrites.setUserEnabled('5', false, result.current.detail.data!.version));
    h.calls[base + 1].reply.resolve(json({ userId: 5, version: 4 })); await promise;
    result.current.detail.reload();
    await waitFor(() => expect(h.calls).toHaveLength(base + 3));
    h.calls[base + 2].reply.resolve(new Response(null, { status: 503 })); await flushPromises();
    await waitFor(() => expect(result.current.detail.error).toBe('服务暂不可用，请稍后重试。'));
    expect(result.current.write.phase).toBe('confirmed');
    result.current.detail.reload();
    await waitFor(() => expect(h.calls).toHaveLength(base + 4));
    expect(h.calls.slice(base).map(call => call.init.method ?? 'GET')).toEqual(['GET', 'PATCH', 'GET', 'GET']);
  });
});

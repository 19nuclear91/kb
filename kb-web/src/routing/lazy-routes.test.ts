import { defineComponent, h, onMounted, watch, type Component } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import App from '../App.vue';
import { useSession } from '../SessionProvider';
import { Session } from '../session';
import { mountRouter } from '../test/app';
import { deferred, json, me, storage } from '../test/fixtures';
import { lazyPage, type PageModule } from './lazyPage';
import { reloadPage } from './reloadPage';

const registry = vi.hoisted(() => ({ names: [] as string[], pages: {} as Record<string, Component> }));
vi.mock('./lazyPages', async importOriginal => {
  const actual = await importOriginal<typeof import('./lazyPages')>();
  registry.names = Object.keys(actual);
  return Object.defineProperties({}, Object.fromEntries(registry.names.map(name => [name, { enumerable: true, get: () => registry.pages[name] }])));
});
vi.mock('./reloadPage', () => ({ reloadPage: vi.fn() }));
vi.mock('../pages/LoginPage.vue', () => import('../test/stubs').then(m => m.stubFromPage('登录入口：')));
vi.mock('../pages/ChangePasswordPage.vue', () => import('../test/stubs').then(m => m.stubPage('改密入口')));

const pending: Record<string, ReturnType<typeof deferred<PageModule>>> = {};
const loaders: Record<string, ReturnType<typeof vi.fn>> = {};
const mounted = vi.fn();
beforeEach(() => {
  mounted.mockClear(); vi.mocked(reloadPage).mockClear();
  vi.spyOn(console, 'error').mockImplementation(() => {});
  for (const name of registry.names) {
    pending[name] = deferred<PageModule>();
    const loader = vi.fn(() => pending[name].promise); loaders[name] = loader;
    registry.pages[name] = lazyPage(loader);
  }
});
afterEach(() => { Reflect.deleteProperty(document, 'visibilityState'); });
/** 对应 React 版的 useEffect 探针：挂载后及依赖变化时记录并读取当前目标。 */
const Probe = defineComponent({ props: { name: { type: String, required: true } }, setup(props) {
  const auth = useSession(), route = useRoute();
  const target = () => String(route.params.documentId ?? route.params.spaceId ?? '');
  const run = () => {
    mounted(props.name, target(), auth.state.epoch, auth.state.me?.user.userId);
    void auth.session.json(`/api/probe/${props.name}/${target()}`).catch(() => {});
  };
  onMounted(run);
  watch([() => auth.session, () => auth.state.epoch, () => auth.state.me?.user.userId, () => props.name, target], run);
  return () => h('h1', `${props.name}:${target()}`);
} });
async function finish(name: string, rejected = false) {
  if (rejected) pending[name].reject(new Error('private download URL'));
  else pending[name].resolve({ default: defineComponent({ setup: () => () => h(Probe, { name }) }) });
  await flushPromises();
}
const Controls = defineComponent({ setup() {
  const router = useRouter();
  return () => [h('button', { onClick: () => void router.push('/documents/2') }, '文档 B'),
    h('button', { onClick: () => void router.push('/admin/spaces/2') }, '空间 B'),
    h('button', { onClick: () => void router.push('/admin/users') }, '账号地址')];
} });
function harness(path: string, options: { saved?: boolean; identity?: typeof me | Response | Promise<Response>; permissions?: string[] | Promise<Response> } = {}) {
  let identity = options.identity ?? me;
  let permissions = options.permissions ?? ['identity:user:read', 'identity:role:read', 'space:create', 'operations:reports:read'];
  const transport = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === '/api/auth/me') return identity instanceof Promise ? identity : identity instanceof Response ? identity.clone() : json(identity);
    if (url === '/api/auth/permissions') return permissions instanceof Promise ? permissions : json(permissions);
    if (url === '/api/admin/spaces') return json([]);
    if (url === '/api/notifications/unread-count') return json({ count: '0', asOf: '2026-11-06T00:00:00Z' });
    return json({});
  });
  const session = new Session(storage(options.saved ?? true), transport as typeof fetch);
  const view = mountRouter(() => [h(App, { session }), h(Controls)], { path });
  return { session, transport, view, identity: (value: typeof identity) => { identity = value; }, permissions: (value: typeof permissions) => { permissions = value; } };
}
const expectOnly = (name: string) => {
  expect(loaders[name]).toHaveBeenCalledTimes(1);
  for (const other of registry.names.filter(value => value !== name)) expect(loaders[other]).not.toHaveBeenCalled();
};

it.each(['/documents/1', '/admin/users'])('地址 %s 在身份未确认、匿名、改密或故障时不调用加载器', async path => {
  for (const options of [{ saved: false }, { identity: { ...me, mustChangePassword: true } },
    { identity: deferred<Response>().promise }, { identity: new Response(null, { status: 503 }) }]) {
    const h = harness(path, options);
    await flushPromises();
    for (const loader of Object.values(loaders)) expect(loader).not.toHaveBeenCalled();
    if (options.saved === false) expect(screen.getByText(`登录入口：${path}`)).toBeTruthy();
    h.view.unmount();
  }
});
it.each([false, true])('只加载选中页，菜单不预取；退出后迟到拒绝=%s 无影响', async rejected => {
  const h = harness('/documents/1'); await waitFor(() => expect(loaders.DocumentDetailPage).toHaveBeenCalledTimes(1));
  const link = await screen.findByRole('link', { name: /账号管理/ }); await fireEvent.mouseEnter(link); await fireEvent.focus(link);
  expectOnly('DocumentDetailPage'); expect(screen.getByText('正在加载页面…')).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: '退出登录' }));
  await screen.findByText(/登录入口/); await finish('DocumentDetailPage', rejected);
  expect(mounted).not.toHaveBeenCalled(); expect(h.transport.mock.calls.some(([url]) => String(url).startsWith('/api/probe/'))).toBe(false);
});
it('路由 transition 显示新加载状态，已挂载的旧页不能充当新页占位', async () => {
  harness('/documents'); await waitFor(() => expect(loaders.DocumentsPage).toHaveBeenCalled()); await finish('DocumentsPage');
  expect(screen.getByRole('heading', { name: 'DocumentsPage:' })).toBeTruthy();
  await fireEvent.click(screen.getByRole('link', { name: /知识搜索/ }));
  await waitFor(() => expect(loaders.SearchPage).toHaveBeenCalled());
  expect(screen.queryByRole('heading', { name: 'DocumentsPage:' })).toBeNull();
  expect(screen.getByText('正在加载页面…')).toBeTruthy();
  await finish('SearchPage'); expect(screen.getByRole('heading', { name: 'SearchPage:' })).toBeTruthy();
});
it.each([false, true])('A/B 快速切页后旧资源拒绝=%s 不覆盖目标页面或发起旧请求', async rejected => {
  harness('/documents'); await waitFor(() => expect(loaders.DocumentsPage).toHaveBeenCalled());
  await fireEvent.click(screen.getByRole('link', { name: /知识搜索/ }));
  await waitFor(() => expect(loaders.SearchPage).toHaveBeenCalled()); await finish('SearchPage'); await finish('DocumentsPage', rejected);
  expect(screen.getByRole('heading', { name: 'SearchPage:' })).toBeTruthy();
  expect(screen.queryByText('页面资源加载失败')).toBeNull();
  expect(mounted.mock.calls.every(([name]) => name === 'SearchPage')).toBe(true);
});
it.each([['/documents/1', 'DocumentDetailPage', '文档 B'], ['/admin/spaces/1', 'SpaceDetailPage', '空间 B']])('同一模块不同资源 %s 只挂载当前目标', async (path, name, button) => {
  harness(path); await waitFor(() => expect(loaders[name]).toHaveBeenCalled());
  await fireEvent.click(screen.getByRole('button', { name: button })); await flushPromises(); await finish(name);
  expect(screen.getByRole('heading', { name: `${name}:2` })).toBeTruthy();
  expect(mounted.mock.calls.every(([page, id]) => page === name && id === '2')).toBe(true);
});
it.each([
  ['/documents/1', 'DocumentDetailPage', 'password'], ['/documents/1', 'DocumentDetailPage', 'unavailable'], ['/documents/1', 'DocumentDetailPage', 'hidden'],
  ['/admin/users', 'UsersPage', 'password'], ['/admin/users', 'UsersPage', 'unavailable'], ['/admin/users', 'UsersPage', 'hidden'],
])('下载 %s 期间进入 %s/%s 后迟到成功不能挂载旧页面', async (path, name, phase) => {
  const h = harness(path); await waitFor(() => expect(loaders[name]).toHaveBeenCalled());
  if (phase === 'hidden') {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    document.dispatchEvent(new Event('visibilitychange')); await flushPromises();
  } else {
    h.identity(phase === 'password' ? { ...me, mustChangePassword: true } : new Response(null, { status: 503 }));
    await h.session.refresh(); await flushPromises();
  }
  await finish(name); expect(mounted).not.toHaveBeenCalled();
  if (phase === 'hidden') {
    h.identity({ ...me, user: { ...me.user, userId: '2' } });
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    document.dispatchEvent(new Event('visibilitychange')); await flushPromises();
    await screen.findByRole('heading', { name: `${name}:${name === 'DocumentDetailPage' ? '1' : ''}` });
    expect(mounted.mock.calls.every(([, , , user]) => user === '2')).toBe(true);
  }
});
it('换号重新确认期间完成的模块只能在新身份确认后读取当前目标', async () => {
  const h = harness('/documents/1'); await waitFor(() => expect(loaders.DocumentDetailPage).toHaveBeenCalled());
  const identity = deferred<Response>(); h.identity(identity.promise);
  const refreshing = h.session.refresh(); await flushPromises();
  await finish('DocumentDetailPage'); expect(mounted).not.toHaveBeenCalled();
  identity.resolve(json({ ...me, user: { ...me.user, userId: '2' } })); await refreshing; await flushPromises();
  expect(mounted.mock.calls.every(([, , , user]) => user === '2')).toBe(true);
  expect(mounted).toHaveBeenCalledTimes(1);
});
it.each([false, true])('管理下载期间撤权，迟到拒绝=%s 不能挂载、请求或覆盖拒绝提示', async rejected => {
  const h = harness('/admin/users'); await waitFor(() => expect(loaders.UsersPage).toHaveBeenCalled());
  h.permissions([]); await h.session.refresh(); await flushPromises();
  await screen.findByText('当前账号没有此管理页面的读取资格。'); await finish('UsersPage', rejected);
  expect(mounted).not.toHaveBeenCalled(); expect(screen.queryByText('页面资源加载失败')).toBeNull();
  expect(screen.getByText('当前账号没有此管理页面的读取资格。')).toBeTruthy();
});
it('管理资格未确认或拒绝不调用加载器，缓存成功后再拒绝也不读取资料', async () => {
  const permissions = deferred<Response>(), h = harness('/admin/users', { permissions: permissions.promise });
  await screen.findByText('正在确认管理权限…'); expect(loaders.UsersPage).not.toHaveBeenCalled();
  permissions.resolve(json(['identity:user:read'])); await flushPromises();
  await waitFor(() => expect(loaders.UsersPage).toHaveBeenCalled()); await finish('UsersPage');
  expect(mounted).toHaveBeenCalledTimes(1); h.permissions([]); await h.session.refresh(); await flushPromises();
  await screen.findByText('当前账号没有此管理页面的读取资格。');
  await fireEvent.click(screen.getByRole('link', { name: /文档中心/ }));
  await fireEvent.click(screen.getByRole('button', { name: '账号地址' }));
  await screen.findByText('当前账号没有此管理页面的读取资格。'); expect(mounted).toHaveBeenCalledTimes(1);
  expect(loaders.UsersPage).toHaveBeenCalledTimes(1);
});
it('管理 A/B 切换后旧失败晚到不覆盖新页，失败可离开及退出且刷新不重放写入', async () => {
  const h = harness('/admin/users'); await waitFor(() => expect(loaders.UsersPage).toHaveBeenCalled());
  await fireEvent.click(screen.getByRole('link', { name: /角色管理/ }));
  await waitFor(() => expect(loaders.RolesPage).toHaveBeenCalled()); await finish('RolesPage'); await finish('UsersPage', true);
  expect(screen.getByRole('heading', { name: 'RolesPage:' })).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: '账号地址' }));
  await screen.findByRole('heading', { name: '页面资源加载失败' }); expect(reloadPage).not.toHaveBeenCalled();
  const before = h.transport.mock.calls.length;
  await fireEvent.click(screen.getByRole('button', { name: '刷新页面' })); expect(reloadPage).toHaveBeenCalledTimes(1);
  expect(h.transport).toHaveBeenCalledTimes(before);
  await fireEvent.click(screen.getByRole('link', { name: /角色管理/ })); await screen.findByRole('heading', { name: 'RolesPage:' });
  await fireEvent.click(screen.getByRole('button', { name: '退出登录' })); await screen.findByText(/登录入口/);
  expect(mounted.mock.calls.every(([name]) => name === 'RolesPage')).toBe(true);
});
it('运营报表只由独立权限加载，无空间目录依赖或菜单预取', async () => {
  const h = harness('/admin/reports/operations', { permissions: ['operations:reports:read'] });
  await waitFor(() => expect(loaders.OperationsReportsPage).toHaveBeenCalled()); expectOnly('OperationsReportsPage');
  expect(h.transport.mock.calls.some(([url]) => url === '/api/admin/spaces')).toBe(false);
});

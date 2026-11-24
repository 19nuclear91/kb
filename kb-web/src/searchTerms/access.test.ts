import { beforeEach, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { screen, waitFor } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { Session } from '../session';
import { mountRouter } from '../test/app';
import { deferred, json, me, storage } from '../test/fixtures';
import { safeReturnPath } from '../navigation';
import { lazyPage, type PageModule } from '../routing/lazyPage';
import App from '../App.vue';

const registry = vi.hoisted(() => ({ load: vi.fn(), mounted: vi.fn(), page: undefined as any }));
vi.mock('../routing/lazyPages', async original => {
  const actual = await original<typeof import('../routing/lazyPages')>();
  return Object.defineProperty({ ...actual }, 'SearchTermsPage', { enumerable: true, get: () => registry.page });
});
vi.mock('../pages/LoginPage.vue', () => import('../test/stubs').then(module => module.stubFromPage('登录返回：')));
let pending: ReturnType<typeof deferred<PageModule>>;
beforeEach(() => { pending = deferred<PageModule>(); registry.load.mockReset().mockReturnValue(pending.promise); registry.mounted.mockClear(); registry.page = lazyPage(() => registry.load()); });
function harness(codes: string[], saved = true) {
  let current = codes;
  const transport = vi.fn(async (path: RequestInfo | URL) => path === '/api/auth/me' ? json(me) : path === '/api/auth/permissions' ? json(current)
    : path === '/api/admin/spaces' ? new Response(null, { status: 503 }) : json({ count: '0', asOf: '2026-11-24T00:00:00Z' }));
  const session = new Session(storage(saved), transport as typeof fetch), view = mountRouter(() => h(App, { session }), { path: '/admin/search-terms' });
  return { session, view, transport, revoke: () => { current = []; } };
}
async function finish() { pending.resolve({ default: defineComponent({ setup() { registry.mounted(); return () => h('h2', '词典模块'); } }) }); await flushPromises(); }
it('仅固定管理返回路径允许，不保存正文、名称、查询串或子路径', () => {
  expect(safeReturnPath('/admin/search-terms')).toBe('/admin/search-terms');
  for (const path of ['/admin/search-terms?nameQuery=private', '/admin/search-terms#private', '/admin/search-terms/1', '//admin/search-terms']) expect(safeReturnPath(path)).toBe('/documents');
});
it('只读资格可下载模块，空间目录故障不成为词典资格依赖', async () => {
  const result = harness(['search:term:read']); await waitFor(() => expect(registry.load).toHaveBeenCalledOnce()); await finish();
  expect(registry.mounted).toHaveBeenCalledOnce(); expect(result.transport.mock.calls.some(([path]) => path === '/api/admin/spaces')).toBe(false);
});
it.each(['search:term:write', 'ROLE_ADMIN', 'document:tag:read'])('只有 %s 不能加载词典模块或目录', async code => {
  harness([code]); await screen.findByText('当前账号没有此管理页面的读取资格。'); expect(registry.load).not.toHaveBeenCalled();
});
it('下载途中撤权，迟到模块不能挂载', async () => {
  const result = harness(['search:term:read']); await waitFor(() => expect(registry.load).toHaveBeenCalledOnce()); result.revoke(); await result.session.refresh();
  await screen.findByText('当前账号没有此管理页面的读取资格。'); await finish(); expect(registry.mounted).not.toHaveBeenCalled();
});
it('匿名合法管理入口可以安全返回且不提前读目录', async () => {
  const result = harness([], false); await screen.findByText('登录返回：/admin/search-terms'); expect(registry.load).not.toHaveBeenCalled();
  expect(result.transport.mock.calls.some(([path]) => String(path).startsWith('/api/admin/search-terms'))).toBe(false);
});

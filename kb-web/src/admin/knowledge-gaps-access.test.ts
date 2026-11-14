import { defineComponent, h, type Component } from 'vue';
import { useRoute } from 'vue-router';
import { flushPromises } from '@vue/test-utils';
import { screen, waitFor } from '@testing-library/vue';
import { beforeEach, expect, it, vi } from 'vitest';
import App from '../App.vue';
import { safeReturnPath } from '../navigation';
import { useSession } from '../SessionProvider';
import { Session } from '../session';
import { mountRouter } from '../test/app';
import { deferred, me, storage } from '../test/fixtures';
import { response } from '../knowledgeGaps/testSupport';
import { lazyPage, type PageModule } from '../routing/lazyPage';

const registry = vi.hoisted(() => ({ names: [] as string[], pages: {} as Record<string, Component> }));
vi.mock('../routing/lazyPages', async original => {
  const actual = await original<typeof import('../routing/lazyPages')>(); registry.names = Object.keys(actual);
  return Object.defineProperties({}, Object.fromEntries(registry.names.map(name => [name, { enumerable: true, get: () => registry.pages[name] }])));
});
vi.mock('../pages/LoginPage.vue', () => import('../test/stubs').then(m => m.stubFromPage('受理登录返回：')));
vi.mock('../pages/ChangePasswordPage.vue', () => import('../test/stubs').then(m => m.stubPage('必须改密')));
const pending: Record<string, ReturnType<typeof deferred<PageModule>>> = {}, loaders: Record<string, ReturnType<typeof vi.fn>> = {};
const mounted = vi.fn();
beforeEach(() => {
  mounted.mockClear();
  for (const name of registry.names) {
    pending[name] = deferred<PageModule>();
    const loader = vi.fn(() => pending[name].promise);
    loaders[name] = loader; registry.pages[name] = lazyPage(loader);
  }
});
function harness(path: string, initialCodes: string[] = [], saved = true, identity: typeof me | Promise<Response> = me) {
  let codes = initialCodes;
  const transport = vi.fn(async (url: RequestInfo | URL) => {
    if (url === '/api/auth/me') return identity instanceof Promise ? identity : response(identity);
    if (url === '/api/auth/permissions') return response(codes);
    if (url === '/api/admin/spaces') return new Response(null, { status: 503 });
    if (url === '/api/notifications/unread-count') return response({ count: '0', asOf: '2026-11-14T00:00:00Z' });
    return response({});
  });
  const session = new Session(storage(saved), transport as typeof fetch), view = mountRouter(() => h(App, { session }), { path });
  return { session, transport, view, revoke() { codes = []; } };
}
async function finish(name: string) {
  pending[name].resolve({ default: defineComponent({ setup() {
    const auth = useSession(), route = useRoute(); mounted(name, route.params.gapId);
    void auth.session.json('/api/gap-module-probe'); return () => h('h2', name);
  } }) }); await flushPromises();
}
it('受理登录返回仅固定根及精确正整数详情，不接受名称/正文/筛选或未注册路径', () => {
  for (const path of ['/admin/knowledge-gaps', '/admin/knowledge-gaps/123', '/admin/knowledge-gaps/9223372036854775807']) expect(safeReturnPath(path)).toBe(path);
  for (const path of ['/admin/knowledge-gaps?description=private', '/admin/knowledge-gaps?nameQuery=private', '/admin/knowledge-gaps?status=SUBMITTED',
    '/admin/knowledge-gaps/1#x', '/admin/knowledge-gaps/0', '/admin/knowledge-gaps/01', '/admin/knowledge-gaps/9223372036854775808',
    '/admin/knowledge-gaps/1/close', '//admin/knowledge-gaps', 'https://evil.test/admin/knowledge-gaps']) expect(safeReturnPath(path)).toBe('/documents');
});
it.each([['/admin/knowledge-gaps', 'KnowledgeGapQueuePage'], ['/admin/knowledge-gaps/123', 'KnowledgeGapReceptionPage']])
  ('只有 read 可加载 %s，不依赖空间/身份目录或其他页面', async (path, name) => {
    const h = harness(path, ['knowledge:gap:read']); await waitFor(() => expect(loaders[name]).toHaveBeenCalledOnce());
    expect(await screen.findByRole('link', { name: /知识问题受理/ })).toBeTruthy();
    for (const other of registry.names.filter(value => value !== name)) expect(loaders[other]).not.toHaveBeenCalled();
    expect(h.transport.mock.calls.some(([url]) => /\/api\/admin\/(spaces|identity)/.test(String(url)))).toBe(false);
    await finish(name); expect(mounted).toHaveBeenCalledOnce();
  });
it.each(['knowledge:gap:assign', 'knowledge:gap:close', 'knowledge:gap:handle', 'ROLE_ADMIN', 'identity:user:read'])
  ('只有 %s 不下载受理页面或读取队列', async code => {
    const h = harness('/admin/knowledge-gaps', [code]); await screen.findByText('当前账号没有此管理页面的读取资格。');
    expect(loaders.KnowledgeGapQueuePage).not.toHaveBeenCalled(); expect(screen.queryByRole('link', { name: /知识问题受理/ })).toBeNull();
    expect(h.transport.mock.calls.some(([url]) => String(url).startsWith('/api/admin/knowledge-gaps'))).toBe(false);
  });
it('下载期间 read 撤销后迟到模块不能挂载或发旧目标请求', async () => {
  const h = harness('/admin/knowledge-gaps/123', ['knowledge:gap:read']); await waitFor(() => expect(loaders.KnowledgeGapReceptionPage).toHaveBeenCalledOnce());
  h.revoke(); await h.session.refresh(); await screen.findByText('当前账号没有此管理页面的读取资格。'); await finish('KnowledgeGapReceptionPage');
  expect(mounted).not.toHaveBeenCalled(); expect(h.transport.mock.calls.some(([url]) => url === '/api/gap-module-probe')).toBe(false);
});
it('匿名合法详情保留安全返回，身份未确认和改密时不下载或读取受理内容', async () => {
  const anonymous = harness('/admin/knowledge-gaps/123', [], false); await screen.findByText('受理登录返回：/admin/knowledge-gaps/123'); anonymous.view.unmount();
  const waiting = harness('/admin/knowledge-gaps', [], true, deferred<Response>().promise); await flushPromises();
  expect(loaders.KnowledgeGapQueuePage).not.toHaveBeenCalled(); waiting.view.unmount();
  const password = harness('/admin/knowledge-gaps', [], true, { ...me, mustChangePassword: true }); await screen.findByText('必须改密');
  expect(loaders.KnowledgeGapQueuePage).not.toHaveBeenCalled(); password.view.unmount();
});

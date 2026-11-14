import { h } from 'vue';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import App from '../App.vue';
import { safeReturnPath } from '../navigation';
import { Session } from '../session';
import { mountRouter } from '../test/app';
import { deferred, me, storage } from '../test/fixtures';
import { gapRouteKind } from './routes';
import { detail, history, item, page, response } from './testSupport';

vi.mock('../pages/LoginPage.vue', () => import('../test/stubs').then(m => m.stubFromPage('登录返回：')));
vi.mock('../pages/ChangePasswordPage.vue', () => import('../test/stubs').then(m => m.stubPage('先修改密码')));
afterEach(() => { vi.unstubAllEnvs(); Reflect.deleteProperty(document, 'visibilityState'); });

function harness(path: string, options: { saved?: boolean; identity?: typeof me | Promise<Response>; codes?: string[] | Promise<Response> } = {}) {
  let identity = options.identity ?? me, codes = options.codes ?? [];
  const transport = vi.fn(async (url: RequestInfo | URL) => {
    const path = String(url);
    if (path === '/api/auth/me') return identity instanceof Promise ? identity : response(identity);
    if (path === '/api/auth/permissions') return codes instanceof Promise ? codes : response(codes);
    if (path === '/api/admin/spaces') return new Response(null, { status: 503 });
    if (path === '/api/notifications/unread-count') return response({ count: '0', asOf: '2026-11-14T04:00:00Z' });
    if (path.startsWith('/api/knowledge-gaps?')) return response(page([item()]));
    if (/^\/api\/knowledge-gaps\/\d+\/history\?/.test(path)) return response(page([history()]));
    if (/^\/api\/knowledge-gaps\/\d+$/.test(path)) return response(detail({ gapId: BigInt(path.split('/').at(-1)!) }));
    return response({});
  });
  const session = new Session(storage(options.saved ?? true), transport as typeof fetch);
  const view = mountRouter(() => h(App, { session }), { path });
  return { session, transport, view, setCodes(value: string[] | Promise<Response>) { codes = value; },
    setIdentity(value: typeof me | Promise<Response>) { identity = value; } };
}
const gapCalls = (transport: ReturnType<typeof vi.fn>) => transport.mock.calls.filter(([path]) => String(path).startsWith('/api/knowledge-gaps'));

it('普通返回路径仅白名单根/新建/精确正整数详情，拒绝正文、筛选、片段与其他地址', () => {
  for (const path of ['/knowledge-gaps', '/knowledge-gaps/new', '/knowledge-gaps/123', '/knowledge-gaps/9223372036854775807'])
    expect(safeReturnPath(path)).toBe(path);
  for (const path of ['/knowledge-gaps?description=私人内容', '/knowledge-gaps/new?source=SEARCH', '/knowledge-gaps/1#private',
    '/knowledge-gaps?view=assigned', '/knowledge-gaps/0', '/knowledge-gaps/01', '/knowledge-gaps/-1', '/knowledge-gaps/1.0',
    '/knowledge-gaps/9223372036854775808', '/knowledge-gaps/1/notes', '/knowledge-gaps/', '//knowledge-gaps',
    'https://evil.test/knowledge-gaps', '/knowledge-gaps/%31', null]) {
    expect(gapRouteKind(path)).toBeNull(); expect(safeReturnPath(path)).toBe('/documents');
  }
  expect(safeReturnPath('/')).toBe('/documents'); expect(safeReturnPath(undefined)).toBe('/documents');
});

it('AI 关闭且无管理权限仍可直达本人列表；不依赖空间/身份目录或聊天 API', async () => {
  vi.stubEnv('VITE_CONVERSATION_ENABLED', 'false');
  const h = harness('/knowledge-gaps');
  expect(await screen.findByRole('heading', { name: '知识需求' })).toBeTruthy();
  expect(await screen.findByRole('link', { name: '问题 9007199254740993' })).toBeTruthy();
  expect((await screen.findByRole('link', { name: /知识需求/ })).getAttribute('aria-current')).toBe('page');
  expect(gapCalls(h.transport).map(([path]) => path)).toEqual(['/api/knowledge-gaps?view=mine&limit=20']);
  expect(h.transport.mock.calls.some(([path]) => /\/api\/(admin\/spaces|admin\/identity|conversations|chat|search)/.test(String(path)))).toBe(false);
  expect(screen.queryByRole('button', { name: '分派给我' })).toBeNull();
});

it('新建和详情支持直接访问，初始化不创建、不读旧聊天，合法 ID 保持精确', async () => {
  const newPage = harness('/knowledge-gaps/new');
  expect(await screen.findByRole('heading', { name: '提出知识需求' })).toBeTruthy();
  expect(await screen.findByRole('textbox', { name: '明确分享的描述' })).toBeTruthy();
  expect(gapCalls(newPage.transport)).toHaveLength(0);
  newPage.view.unmount();
  const h = harness('/knowledge-gaps/9223372036854775807');
  expect(await screen.findByText('明确分享的描述')).toBeTruthy();
  await screen.findByText('已到处理历史末页。');
  expect(gapCalls(h.transport).map(([path]) => path)).toEqual(['/api/knowledge-gaps/9223372036854775807',
    '/api/knowledge-gaps/9223372036854775807/history?limit=20']);
});

it.each(['/knowledge-gaps/0', '/knowledge-gaps/01', '/knowledge-gaps/9223372036854775808', '/knowledge-gaps/1?description=private'])
  ('非法地址 %s 不发问题请求', async path => {
    const h = harness(path);
    expect(await screen.findByText('知识问题地址无效，请从知识需求入口重新进入。')).toBeTruthy();
    expect(gapCalls(h.transport)).toHaveLength(0);
  });

it('分派给我须当前 handle；权限未确认不探测 assigned，只有 handle 无 read 也可用', async () => {
  const pending = deferred<Response>(), h = harness('/knowledge-gaps', { codes: pending.promise });
  await screen.findByRole('link', { name: '问题 9007199254740993' });
  expect(screen.queryByRole('button', { name: '分派给我' })).toBeNull();
  pending.resolve(response(['knowledge:gap:handle']));
  const assigned = await screen.findByRole('button', { name: '分派给我' });
  await fireEvent.click(assigned);
  await waitFor(() => expect(gapCalls(h.transport).some(([path]) => String(path).includes('view=assigned'))).toBe(true));
  expect(h.transport.mock.calls.some(([path]) => String(path).startsWith('/api/admin/knowledge-gaps'))).toBe(false);
  h.setCodes([]); await h.session.refresh(); await flushPromises();
  expect(screen.queryByRole('button', { name: '分派给我' })).toBeNull();
  expect(gapCalls(h.transport).filter(([path]) => String(path).includes('view=assigned'))).toHaveLength(1);
});

it.each([['/knowledge-gaps', '/knowledge-gaps'], ['/knowledge-gaps/new', '/knowledge-gaps/new'],
  ['/knowledge-gaps/123', '/knowledge-gaps/123'], ['/knowledge-gaps/123?description=private', '/documents']])
  ('匿名访问 %s 使用安全登录返回', async (path, destination) => {
    const h = harness(path, { saved: false });
    expect(await screen.findByText(`登录返回：${destination}`)).toBeTruthy();
    expect(gapCalls(h.transport)).toHaveLength(0);
  });

it('身份未确认/强制改密均不挂载或预取问题页面数据，旧默认落点仍为文档中心', async () => {
  const pending = deferred<Response>(), loading = harness('/knowledge-gaps/123', { identity: pending.promise });
  expect(screen.getByText('正在确认登录状态…')).toBeTruthy();
  await flushPromises(); expect(gapCalls(loading.transport)).toHaveLength(0); loading.view.unmount();
  const password = harness('/knowledge-gaps/new', { identity: { ...me, mustChangePassword: true } });
  expect(await screen.findByText('先修改密码')).toBeTruthy(); expect(gapCalls(password.transport)).toHaveLength(0); password.view.unmount();
  const root = harness('/');
  await waitFor(() => expect(root.view.router.currentRoute.value.path).toBe('/documents'));
});

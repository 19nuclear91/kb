import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import App from '../App';
import { Session } from '../session';
import { SessionProvider } from '../SessionProvider';
import { deferred, json, me, storage } from '../test/fixtures';
import { safeReturnPath } from '../navigation';
import { AdminAccessProvider, useAdminAccess } from './access';
const moduleLoads = vi.hoisted(() => vi.fn());
vi.mock('../routing/lazyPage', async importOriginal => {
  const actual = await importOriginal<typeof import('../routing/lazyPage')>();
  return { ...actual, lazyPage: (load: Parameters<typeof actual.lazyPage>[0]) => actual.lazyPage(() => { moduleLoads(); return load(); }) };
});
vi.mock('../pages/LoginPage', () => ({ LoginPage: () => <div>登录入口</div> }));
vi.mock('../pages/ChangePasswordPage', () => ({ ChangePasswordPage: () => <div>强制改密入口</div> }));
vi.mock('../pages/AccountPage', () => ({ AccountPage: () => <div>账户入口</div> }));
vi.mock('../pages/DocumentsPage', () => ({ DocumentsPage: () => <div>文档入口</div> }));
vi.mock('../pages/DocumentDetailPage', () => ({ DocumentDetailPage: () => <div>详情入口</div> }));
vi.mock('../pages/Search', () => ({ SearchPage: () => <div>搜索入口</div> }));
vi.mock('../pages/Assistant', () => ({ AssistantPage: () => <div>助手入口</div> }));
vi.mock('./SpacesPage', () => ({ SpacesPage: () => <div>知识空间页面</div> }));
vi.mock('./SpaceDetailPage', () => ({ SpaceDetailPage: () => <div>空间详情页面</div> }));
vi.mock('./DocumentPanelPage', () => ({ DocumentPanelPage: () => <div>文档管理页面</div> }));

type Answer = Response | (() => Response);
/** 本人权限按调用顺序依次返回；管理接口按路径应答，其余路径一律 404。 */
function backend(permissions: Answer[], admin: (path: string, init: RequestInit) => Response | undefined = () => undefined, identity = me) {
  const calls: { path: string; method: string }[] = [];
  const transport = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input); calls.push({ path, method: init.method ?? 'GET' });
    if (path === '/api/auth/me') return json(identity);
    if (path === '/api/admin/spaces') return json([]);
    if (path === '/api/auth/permissions') { const next = permissions.length > 1 ? permissions.shift()! : permissions[0]; return typeof next === 'function' ? next() : next.clone(); }
    return admin(path, init) ?? new Response(null, { status: 404 });
  });
  const session = new Session(storage(), transport as typeof fetch);
  const count = (path: string, method = 'GET') => calls.filter(call => call.path === path && call.method === method).length;
  return { session, transport, calls, count };
}
const show = (session: Session, path: string) => render(<MemoryRouter initialEntries={[path]}><App session={session} /></MemoryRouter>);
const unavailable = () => new Response(null, { status: 503 });
const empty = { items: [], hasMore: false, nextCursor: null };
afterEach(() => { Reflect.deleteProperty(document, 'visibilityState'); });

describe('本人权限上下文', () => {
  it('直接地址在身份及管理资格确认前不调用页面加载器，拒绝后保持未加载', async () => {
    const response = deferred<Response>();
    const session = new Session(storage(), vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/auth/me') return json(me);
      if (String(input) === '/api/admin/spaces') return json([]);
      return response.promise;
    }) as typeof fetch);
    show(session, '/admin/users'); expect(moduleLoads).not.toHaveBeenCalled();
    await screen.findByText('正在确认管理权限…'); expect(moduleLoads).not.toHaveBeenCalled();
    await act(async () => response.resolve(json([])));
    await screen.findByText('当前账号没有此管理页面的读取资格。'); expect(moduleLoads).not.toHaveBeenCalled();
  });
  it.each([['/admin/spaces', '知识空间页面'], ['/admin/spaces/7', '空间详情页面'], ['/admin/documents/9', '文档管理页面']])('直接打开新管理路由 %s 使用现有会话', async (path, title) => {
    const h = backend([json(['space:create'])]); show(h.session, path);
    expect(await screen.findByText(title)).toBeTruthy(); expect(h.session.getSnapshot().phase).toBe('ready');
    expect(screen.getByRole('link', { name: /知识空间/ })).toBeTruthy();
  });
  it('权限接口失败只阻止管理区，普通页面与身份保持可用；重试后进入', async () => {
    const h = backend([unavailable(), json(['identity:user:read'])], path => path.startsWith('/api/admin/identity/users/search') ? json(empty) : undefined);
    show(h.session, '/admin/users');
    await screen.findByText('当前无法确认管理权限，管理功能暂不可用。');
    expect(screen.getByRole('link', { name: /文档中心/ })).toBeTruthy(); expect(screen.queryByRole('navigation', { name: '管理导航' })).toBeNull();
    expect(screen.getByText(me.user.displayName)).toBeTruthy(); expect(h.session.getSnapshot().phase).toBe('ready');
    expect(h.calls.some(call => call.path.startsWith('/api/admin/') && call.path !== '/api/admin/spaces')).toBe(false);
    fireEvent.click(screen.getByRole('link', { name: /文档中心/ })); expect(await screen.findByText('文档入口')).toBeTruthy();
    fireEvent.click(screen.getByRole('link', { name: /个人账户/ })); expect(await screen.findByText('账户入口')).toBeTruthy();
    expect(h.count('/api/auth/permissions')).toBe(1);
  });
  it('不可确认时显式重试；成功空权限是确实无资格，不请求管理数据', async () => {
    const h = backend([unavailable(), json(['identity:user:read'])], path => path.startsWith('/api/admin/identity/users/search') ? json(empty) : undefined);
    show(h.session, '/admin/users');
    fireEvent.click(await screen.findByRole('button', { name: '重新确认' }));
    await waitFor(() => expect(h.count('/api/admin/identity/users/search')).toBe(1));
    expect(screen.getByRole('link', { name: /账号管理/ })).toBeTruthy(); expect(h.count('/api/auth/permissions')).toBe(2);
    const none = backend([json([])]); show(none.session, '/admin/roles');
    expect(await screen.findAllByText('当前账号没有此管理页面的读取资格。')).toHaveLength(1);
    expect(none.calls.some(call => call.path.startsWith('/api/admin/') && call.path !== '/api/admin/spaces')).toBe(false);
  });
  it('强制改密前不预取管理权限或数据', async () => {
    const h = backend([json(['identity:user:read'])], undefined, { ...me, mustChangePassword: true });
    show(h.session, '/admin/users');
    await screen.findByText('强制改密入口');
    expect(h.calls.map(call => call.path)).toEqual(['/api/auth/me']);
  });
  it('按完整 code 精确判断：角色名、前缀或非法响应不放行', async () => {
    const h = backend([json(['ROLE_ADMIN', 'identity:*', 'identity:user', 'IDENTITY:USER:READ', 'identity:user:read '])], undefined, { ...me, roles: ['ROLE_ADMIN'] });
    show(h.session, '/admin/users');
    await screen.findByText('当前账号没有此管理页面的读取资格。');
    expect(screen.queryByRole('navigation', { name: '管理导航' })).toBeNull();
    const broken = backend([json(['identity:user:read', 7])]); const view = show(broken.session, '/admin/users');
    expect(await within(view.container).findByText('当前无法确认管理权限，管理功能暂不可用。')).toBeTruthy();
  });
});

function Probe() {
  const access = useAdminAccess();
  return <><output>{access.status}:{['identity:user:read', 'identity:role:read'].filter(access.has).join(',')}</output><button onClick={() => access.check(true)}>强制核对</button></>;
}
describe('权限核对时机', () => {
  it('晚到的权限响应不覆盖较新的核对，会话恢复后的旧响应被丢弃', async () => {
    const replies: ReturnType<typeof deferred<Response>>[] = [];
    const transport = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/auth/me') return json(me);
      if (String(input) === '/api/admin/spaces') return json([]);
      const reply = deferred<Response>(); replies.push(reply); return reply.promise;
    });
    const session = new Session(storage(), transport as typeof fetch);
    render(<SessionProvider session={session}><AdminAccessProvider><Probe /></AdminAccessProvider></SessionProvider>);
    await waitFor(() => expect(session.getSnapshot().phase).toBe('ready'));
    await waitFor(() => expect(replies.length).toBeGreaterThan(0));
    const older = replies.at(-1)!;
    fireEvent.click(screen.getByRole('button', { name: '强制核对' }));
    const newer = replies.at(-1)!; expect(newer).not.toBe(older);
    await act(async () => { newer.resolve(json(['identity:role:read'])); });
    await screen.findByText('ready:identity:role:read');
    await act(async () => { older.resolve(json(['identity:user:read'])); });
    expect(screen.getByText('ready:identity:role:read')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '强制核对' }));
    const beforeSuspend = replies.at(-1)!;
    act(() => session.suspend()); expect(screen.getByText('loading:')).toBeTruthy();
    await act(async () => { await session.refresh(); });
    const afterRestore = replies.at(-1)!; expect(afterRestore).not.toBe(beforeSuspend);
    await act(async () => { beforeSuspend.resolve(json(['identity:user:read', 'identity:role:read'])); });
    expect(screen.getByText('loading:')).toBeTruthy();
    await act(async () => { afterRestore.resolve(json([])); });
    await screen.findByText('ready:');
  });
  it('进入管理路由核对；后台恢复重新确认身份与权限并重新读取', async () => {
    const h = backend([json(['identity:dept:read'])], path => path === '/api/admin/identity/departments' ? json([]) : undefined);
    show(h.session, '/documents');
    fireEvent.click(await screen.findByRole('link', { name: /部门管理/ }));
    await waitFor(() => expect(h.count('/api/admin/identity/departments')).toBe(1));
    expect(h.count('/api/auth/permissions')).toBe(2);
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    act(() => { document.dispatchEvent(new Event('visibilitychange')); });
    expect(screen.getByText('正在确认登录状态…')).toBeTruthy(); expect(screen.queryByRole('heading', { name: '部门管理' })).toBeNull();
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
    act(() => { document.dispatchEvent(new Event('visibilitychange')); });
    await screen.findByRole('heading', { name: '部门管理' });
    await waitFor(() => expect(h.count('/api/admin/identity/departments')).toBeGreaterThanOrEqual(2));
    const stable = h.calls.length;
    await act(() => new Promise(resolve => setTimeout(resolve, 40)));
    expect(h.calls.length).toBe(stable);
    expect(h.count('/api/admin/identity/departments')).toBeLessThanOrEqual(3);
    expect(h.count('/api/auth/me')).toBe(2); expect(h.count('/api/auth/permissions')).toBeGreaterThanOrEqual(3);
  });
  it('本人撤销角色后失去读取资格：关闭表单并清空管理数据', async () => {
    const self = { userId: '1', username: 'alice', displayName: '小林', departmentId: null, clearanceLevel: 1, enabled: true, version: 1 };
    const h = backend([json(['identity:user:read', 'identity:role:assign']), json([])], (path, init) => {
      if (path.startsWith('/api/admin/identity/users/search')) return json({ items: [self], hasMore: false, nextCursor: null });
      if (path === '/api/admin/identity/users/1') return json(self);
      if (path === '/api/admin/identity/users/1/roles') return json({ items: [{ roleId: '3', code: 'ROLE_ADMIN', name: '管理员' }], hasMore: false, nextCursor: null });
      if (path === '/api/admin/identity/users/1/roles/3' && init.method === 'DELETE') return json(null);
    });
    show(h.session, '/admin/users');
    fireEvent.click(await screen.findByRole('button', { name: '查看 alice' }));
    fireEvent.click(await screen.findByRole('button', { name: '解除角色 ROLE_ADMIN' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认解除' }));
    await screen.findByText('当前账号没有此管理页面的读取资格。');
    expect(screen.queryByText('账号详情')).toBeNull(); expect(screen.queryByText('解除角色绑定')).toBeNull(); expect(screen.queryByText('alice')).toBeNull();
    expect(screen.queryByRole('navigation', { name: '管理导航' })).toBeNull();
    expect(h.count('/api/admin/identity/users/1/roles/3', 'DELETE')).toBe(1); expect(h.count('/api/auth/permissions')).toBe(2);
  });
  it('管理 403 只触发一次 me 与权限核对，不循环核对或要求重新登录；核对故障时停在可重试状态', async () => {
    const h = backend([json(['identity:user:read']), json(['identity:user:read']), unavailable()],
      path => path.startsWith('/api/admin/identity/users/search') ? new Response(null, { status: 403 }) : undefined);
    show(h.session, '/admin/users');
    await screen.findByText('当前操作被拒绝。');
    await act(() => new Promise(resolve => setTimeout(resolve, 50)));
    expect([h.count('/api/auth/me'), h.count('/api/auth/permissions'), h.count('/api/admin/identity/users/search')]).toEqual([2, 2, 1]);
    expect(h.session.getSnapshot().phase).toBe('ready'); expect(screen.queryByText('登录入口')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await screen.findByText('当前无法确认管理权限，管理功能暂不可用。');
    await act(() => new Promise(resolve => setTimeout(resolve, 50)));
    expect([h.count('/api/auth/me'), h.count('/api/auth/permissions'), h.count('/api/admin/identity/users/search')]).toEqual([3, 3, 2]);
  });
});

describe('文档管理面板入口', () => {
  const link = (name: string) => screen.queryByRole('link', { name: new RegExp(name) });
  it('仅密级权限显示文档管理面板入口而不显示知识空间入口，且不触发任何文档或空间请求', async () => {
    const h = backend([json(['document:security-level:write'])]); show(h.session, '/documents');
    expect(await screen.findByRole('link', { name: /文档管理面板/ })).toBeTruthy(); expect(link('知识空间')).toBeNull();
    expect(link('文档管理面板')!.getAttribute('href')).toBe('/admin/documents');
    expect(h.calls.some(call => call.path.startsWith('/api/admin/documents') || call.path.startsWith('/api/admin/spaces/') || call.path.startsWith('/api/documents'))).toBe(false);
    expect(h.count('/api/admin/spaces')).toBe(1);
  });
  it('仅 space:create 同时显示两个入口；无资格时都不显示', async () => {
    const h = backend([json(['space:create'])]); const view = show(h.session, '/documents');
    expect(await screen.findByRole('link', { name: /文档管理面板/ })).toBeTruthy(); expect(link('知识空间')).toBeTruthy(); view.unmount();
    const none = backend([json([])]); show(none.session, '/documents');
    await screen.findByText('文档入口'); await waitFor(() => expect(none.count('/api/auth/permissions')).toBe(1));
    expect(link('文档管理面板')).toBeNull(); expect(link('知识空间')).toBeNull(); expect(screen.queryByRole('navigation', { name: '管理导航' })).toBeNull();
    expect(none.calls.some(call => call.path.startsWith('/api/admin/documents'))).toBe(false);
  });
  it('/admin/documents 是合法站内返回地址，返回后重新核对资格才展示输入框；不带 ID 以外的路径或查询', async () => {
    expect(safeReturnPath('/admin/documents')).toBe('/admin/documents'); expect(safeReturnPath('/admin/documents/9')).toBe('/admin/documents/9');
    for (const path of ['/admin/documents/', '/admin/documents/0', '/admin/documents?documentId=9', '/admin/documents#9', 'https://outside.test/admin/documents', '//admin/documents']) expect(safeReturnPath(path)).toBe('/documents');
    const h = backend([json(['document:security-level:write'])]); const view = show(h.session, '/admin/documents');
    expect(await screen.findByLabelText('文档 ID')).toBeTruthy(); expect(h.count('/api/auth/permissions')).toBe(1);
    expect(h.calls.some(call => call.path.startsWith('/api/admin/documents'))).toBe(false); view.unmount();
    const none = backend([json([])]); show(none.session, '/admin/documents');
    await screen.findByText('当前账号没有此管理页面的读取资格。'); expect(screen.queryByLabelText('文档 ID')).toBeNull();
    expect(none.calls.some(call => call.path.startsWith('/api/admin/documents'))).toBe(false);
  });
});

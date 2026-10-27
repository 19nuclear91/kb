import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { SessionState } from './session';
import { me } from './test/fixtures';
const context = vi.hoisted(() => ({ state: {} as SessionState, session: { logout: vi.fn(), refresh: vi.fn(), json: vi.fn(), getSnapshot: () => context.state } }));
vi.mock('./SessionProvider', () => ({ useSession: () => context }));
vi.mock('./pages/AuthPages', () => ({ LoginPage: () => <div>登录入口</div>, ChangePasswordPage: () => <div>强制改密入口</div>, AccountPage: () => <div>账户入口</div> }));
vi.mock('./pages/Documents', () => ({ DocumentsPage: () => <div>文档入口</div>, DocumentDetailPage: () => <div>详情入口</div> }));
vi.mock('./pages/Search', () => ({ SearchPage: () => <div>搜索入口</div> }));
vi.mock('./pages/Assistant', () => ({ AssistantPage: () => <div>助手入口</div> }));
vi.mock('./admin/UsersPage', () => ({ UsersPage: () => <div>账号管理入口</div> }));
vi.mock('./admin/DepartmentsPage', () => ({ DepartmentsPage: () => <div>部门管理入口</div> }));
vi.mock('./admin/RolesPage', () => ({ RolesPage: () => <div>角色管理入口</div> }));
import { AppRoutes } from './App';
function Location() { return <output>{useLocation().pathname}</output>; }
function From() { return <output>from:{String(useLocation().state?.from ?? '')}</output>; }
beforeEach(() => { context.state = { phase: 'ready', me, epoch: 1 }; context.session.json.mockResolvedValue([]); });
function show(path: string) { return render(<MemoryRouter initialEntries={[path]}><AppRoutes /><Location /><From /></MemoryRouter>); }
it('初始地址默认文档，保留合法详情路由，未知页面明确提示', () => {
  const view = show('/'); expect(screen.getByText('文档入口')).toBeTruthy(); expect(screen.getByText('/documents')).toBeTruthy(); view.unmount();
  const detail = show('/documents/9223372036854775807'); expect(screen.getByText('详情入口')).toBeTruthy(); detail.unmount();
  show('/unknown'); expect(screen.getByRole('heading', { name: '页面不存在' })).toBeTruthy();
});
it('刷新确认身份期间遮蔽保护子页面', () => {
  context.state.phase = 'loading'; show('/documents/1'); expect(screen.queryByText('详情入口')).toBeNull(); expect(screen.getByText('正在确认登录状态…')).toBeTruthy();
});
it('审核路由支持安全登录返回，身份未确认时不预取审核数据', () => {
  for (const phase of ['anonymous', 'loading', 'password'] as const) {
    context.state.phase = phase;
    const view = show('/reviews');
    expect(screen.queryByRole('heading', { name: '版本审核' })).toBeNull();
    if (phase === 'anonymous') expect(screen.getByText('from:/reviews')).toBeTruthy();
    expect(context.session.json).not.toHaveBeenCalled();
    view.unmount();
  }
});
it('通知路由支持安全登录返回；身份未确认、匿名或改密时不预取通知或计数', () => {
  for (const phase of ['anonymous', 'loading', 'password'] as const) {
    context.state.phase = phase;
    const view = show('/notifications');
    expect(screen.queryByRole('heading', { name: '站内通知' })).toBeNull();
    if (phase === 'anonymous') expect(screen.getByText('from:/notifications')).toBeTruthy();
    expect(context.session.json).not.toHaveBeenCalled();
    view.unmount();
  }
});
it('普通有效用户无需管理资格即可进入通知页，只读取本人通知与计数', async () => {
  context.session.json.mockImplementation(async (path: string) => path === '/api/auth/permissions' ? []
    : path === '/api/notifications/unread-count' ? { count: '0', asOf: '2026-10-25T00:00:00Z' }
    : { items: [], hasMore: false, nextCursor: null, asOf: '2026-10-25T00:00:00Z' });
  show('/notifications');
  expect(screen.getByRole('heading', { name: '站内通知' })).toBeTruthy();
  expect(await screen.findByText('暂无可见通知')).toBeTruthy();
  const paths = context.session.json.mock.calls.map(([path]) => String(path));
  expect(paths).toContain('/api/notifications?limit=20'); expect(paths).toContain('/api/notifications/unread-count');
  // 布局既有的本人权限与可管理空间探测之外，不预取身份、部门或审计目录。
  expect(paths.filter(path => !['/api/auth/permissions', '/api/admin/spaces', '/api/notifications?limit=20',
    '/api/notifications/unread-count'].includes(path))).toEqual([]);
});
it('未登录与强制改密只渲染对应入口，不预取业务子页面', () => {
  context.state.phase = 'anonymous'; const view = show('/search'); expect(screen.getByText('登录入口')).toBeTruthy(); expect(screen.queryByText('搜索入口')).toBeNull(); view.unmount();
  context.state.phase = 'password'; show('/documents'); expect(screen.getByText('强制改密入口')).toBeTruthy(); expect(screen.queryByText('文档入口')).toBeNull();
});
it('身份故障展示重试/退出，不保留旧业务页面', () => {
  context.state = { phase: 'unavailable', me: null, epoch: 2, notice: '当前身份不可确认' }; show('/assistant/1');
  expect(screen.getByText('当前身份不可确认')).toBeTruthy(); expect(screen.queryByText('助手入口')).toBeNull();
});
it('三个管理路由可直接访问，导航按各自读取权限显示', async () => {
  context.session.json.mockResolvedValue(['identity:user:read', 'identity:role:read']);
  const view = show('/admin/users');
  expect(screen.getByText('正在确认管理权限…')).toBeTruthy(); expect(screen.queryByText('账号管理入口')).toBeNull();
  expect(await screen.findByText('账号管理入口')).toBeTruthy(); expect(context.session.json).toHaveBeenCalledWith('/api/auth/permissions', expect.anything());
  const nav = screen.getByRole('navigation', { name: '管理导航' });
  expect([...nav.querySelectorAll('a')].map(link => link.getAttribute('href'))).toEqual(['/admin/users', '/admin/roles']);
  view.unmount();
  const roles = show('/admin/roles'); expect(await screen.findByText('角色管理入口')).toBeTruthy(); roles.unmount();
  show('/admin/departments'); expect(await screen.findByText('当前账号没有此管理页面的读取资格。')).toBeTruthy(); expect(screen.queryByText('部门管理入口')).toBeNull();
});
it('刷新入口先确认身份；未登录访问管理路由只保留精确站内返回地址', () => {
  context.state.phase = 'loading'; const view = show('/admin/roles'); expect(screen.getByText('正在确认登录状态…')).toBeTruthy(); expect(context.session.json).not.toHaveBeenCalled(); view.unmount();
  context.state.phase = 'anonymous';
  for (const path of ['/admin/users', '/admin/departments', '/admin/roles']) { const login = show(path); expect(screen.getByText('登录入口')).toBeTruthy(); expect(screen.getByText('/login')).toBeTruthy(); expect(screen.getByText(`from:${path}`)).toBeTruthy(); login.unmount(); }
  for (const path of ['/admin', '/admin/users/1', '/admin/audits/other']) { const login = show(path); expect(screen.getByText('from:/documents')).toBeTruthy(); login.unmount(); }
  expect(context.session.json).not.toHaveBeenCalled();
});
it('无读取资格或仅有写权限时不进入管理页面，也不显示管理导航', async () => {
  context.session.json.mockResolvedValue(['identity:user:write', 'identity:dept:write', 'identity:role:write', 'identity:user:password', 'identity:role:assign']);
  const view = show('/admin/users'); expect(await screen.findByText('当前账号没有此管理页面的读取资格。')).toBeTruthy();
  expect(screen.queryByText('账号管理入口')).toBeNull(); expect(screen.queryByRole('navigation', { name: '管理导航' })).toBeNull();
  expect(screen.getByRole('navigation', { name: '主要导航' })).toBeTruthy(); view.unmount();
  context.session.json.mockResolvedValue([]); show('/admin/departments');
  expect(await screen.findByText('当前账号没有此管理页面的读取资格。')).toBeTruthy(); expect(screen.queryByText('部门管理入口')).toBeNull();
});

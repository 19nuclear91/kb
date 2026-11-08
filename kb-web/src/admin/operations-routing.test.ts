import { beforeEach, expect, it, vi } from 'vitest';
import { h, reactive } from 'vue';
import { screen } from '@testing-library/vue';
import type { SessionState } from '../session';
import { me } from '../test/fixtures';
import { mountRouter } from '../test/app';
import { safeReturnPath } from '../navigation';
const moduleLoads = vi.hoisted(() => vi.fn());
vi.mock('../routing/lazyPage', async importOriginal => {
  const actual = await importOriginal<typeof import('../routing/lazyPage')>();
  return { ...actual, lazyPage: (load: Parameters<typeof actual.lazyPage>[0]) => actual.lazyPage(() => { moduleLoads(); return load(); }) };
});
const h$ = reactive({ state: {} as SessionState, codes: [] as string[], session: { json: vi.fn(), refresh: vi.fn(), logout: vi.fn(), getSnapshot: () => h$.state } });
vi.mock('../SessionProvider', () => ({ useSession: () => h$ }));
vi.mock('./EmbeddingPage.vue', () => import('../test/stubs').then(m => m.stubPage('向量页面已加载')));
vi.mock('./ParseDeadLettersPage.vue', () => import('../test/stubs').then(m => m.stubPage('死信页面已加载')));
vi.mock('../pages/LoginPage.vue', () => import('../test/stubs').then(m => m.stubFromPage('返回:')));
vi.mock('../pages/ChangePasswordPage.vue', () => import('../test/stubs').then(m => m.stubPage('改密入口')));
vi.mock('../pages/AccountPage.vue', () => import('../test/stubs').then(m => m.stubPage('账户')));
import AppRoutes from '../routing/AppRoutes.vue';
const show = (path: string) => mountRouter(() => h(AppRoutes), { path });
beforeEach(() => { h$.state = { phase: 'ready', me, epoch: 1 }; h$.codes = []; h$.session.json.mockReset(); h$.session.json.mockImplementation(async (path: string) => path === '/api/auth/permissions' ? h$.codes : []); });
it('运维资格未确认和拒绝时连页面模块也不加载', async () => {
  h$.session.json.mockImplementation(() => new Promise(() => {}));
  const waiting = show('/admin/embedding');
  expect(screen.getByText('正在确认管理权限…')).toBeTruthy(); expect(moduleLoads).not.toHaveBeenCalled(); waiting.unmount();
  h$.session.json.mockResolvedValue([]); show('/admin/parse/dead-letters');
  await screen.findByText('当前账号没有此管理页面的读取资格。'); expect(moduleLoads).not.toHaveBeenCalled();
});
it.each([['/admin/embedding', 'embedding:read', '向量页面已加载'], ['/admin/parse/dead-letters', 'parse:deadletter:read', '死信页面已加载']])('新路由 %s 在内存中按读取权限映射', async (path, code, label) => {
  h$.codes = [code]; show(path); expect(await screen.findByText(label)).toBeTruthy(); expect(screen.getByRole('link', { name: code === 'embedding:read' ? /向量重建/ : /解析死信/ })).toBeTruthy();
});
it.each(['/admin/embedding', '/admin/parse/dead-letters'])('未登录的 %s 保留安全站内返回地址', async path => {
  h$.state.phase = 'anonymous'; show(path); expect(await screen.findByText(`返回:${path}`)).toBeTruthy(); expect(h$.session.json).not.toHaveBeenCalled();
});
it('强制改密期间不预取运维资料', async () => { h$.state.phase = 'password'; show('/admin/embedding'); expect(await screen.findByText('改密入口')).toBeTruthy(); expect(h$.session.json).not.toHaveBeenCalled(); });
it('写权限、角色名与前缀不开放管理页面', async () => {
  h$.codes = ['embedding:rebuild', 'parse:deadletter:replay', 'ROLE_ADMIN', 'embedding:*']; show('/admin/embedding');
  await screen.findByText('当前账号没有此管理页面的读取资格。'); expect(screen.queryByText('向量页面已加载')).toBeNull(); expect(screen.queryByRole('link', { name: /向量重建/ })).toBeNull();
});
it('返回白名单只接受两条精确路径', () => {
  for (const path of ['/admin/embedding', '/admin/parse/dead-letters']) expect(safeReturnPath(path)).toBe(path);
  for (const path of ['/admin/embedding?reason=private', '/admin/parse/dead-letters/9', '/admin/embedding/other', '//outside.test']) expect(safeReturnPath(path)).toBe('/documents');
});

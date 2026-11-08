import { beforeEach, describe, expect, it, vi } from 'vitest';
import { h, nextTick } from 'vue';
import { render, screen } from '@testing-library/vue';
import { safeReturnPath } from '../navigation';
import { provideAccess } from '../test/access';
import { mountRouter } from '../test/app';
import AdminGuard from './AdminGuard.vue';
import AdminNavigation from './AdminNavigation.vue';
import { emptyAdminAccess, perm, type AdminAccess } from './access';
vi.mock('./access', async original => (await import('../test/access')).withFakeAccess(await original()));
const check = vi.fn();
function withAccess(codes: string[], overrides: Partial<AdminAccess> = {}) {
  provideAccess({ ...emptyAdminAccess, status: 'ready', has: code => codes.includes(code), check, ...overrides });
}
beforeEach(() => { check.mockReset(); provideAccess(null); });
describe('审计入口隔离单元', () => {
  it('守卫只按 audit:read 放行，不依赖身份目录与空间读取', () => {
    withAccess([perm.auditRead], { spaceStatus: 'unavailable' }); render(AdminGuard, { props: { code: perm.auditRead, title: '审计查询' }, slots: { default: () => h('span', '允许读取审计') } });
    expect(screen.getByText('允许读取审计')).toBeTruthy(); expect(check).toHaveBeenCalledTimes(1);
  });
  it.each(['identity:audit:read', 'ROLE_ADMIN', 'audit:*', 'identity:user:write', 'embedding:rebuild'])('资格 %s 不替代审计读取', code => {
    withAccess([code]); render(AdminGuard, { props: { code: perm.auditRead, title: '审计查询' }, slots: { default: () => h('span', '不应加载') } });
    expect(screen.queryByText('不应加载')).toBeNull(); expect(screen.getByText('当前账号没有此管理页面的读取资格。')).toBeTruthy();
  });
  it.each(['loading', 'unavailable'] as const)('资格 %s 不挂载页面', status => {
    withAccess([perm.auditRead], { status }); render(AdminGuard, { props: { code: perm.auditRead, title: '审计查询' }, slots: { default: () => h('span', '不应加载') } });
    expect(screen.queryByText('不应加载')).toBeNull();
  });
  it('导航在资格变化后显示或移除审计入口', async () => {
    withAccess([perm.auditRead]); mountRouter(() => h(AdminNavigation), { path: '/admin/audits' }); expect(screen.getByRole('link', { name: /审计查询/ }).getAttribute('href')).toBe('/admin/audits');
    withAccess(['identity:audit:read']); await nextTick(); expect(screen.queryByRole('link', { name: /审计查询/ })).toBeNull();
  });
  it('安全返回函数只接受精确页面路径', () => {
    expect(safeReturnPath('/admin/audits')).toBe('/admin/audits');
    for (const path of ['/admin/audits/', '/admin/audits/1', '/admin/audits?loginUsername=private', '/admin/audits#private', 'https://outside.test/admin/audits', '//admin/audits']) expect(safeReturnPath(path)).toBe('/documents');
  });
});

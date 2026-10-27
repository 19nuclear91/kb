import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { ReactNode } from 'react';
import { safeReturnPath } from '../navigation';
import { AdminAccessContext, AdminGuard, AdminNavigation, emptyAdminAccess, perm, type AdminAccess } from './access';
const check = vi.fn();
function withAccess(children: ReactNode, codes: string[], overrides: Partial<AdminAccess> = {}) {
  return <AdminAccessContext value={{ ...emptyAdminAccess, status: 'ready', has: code => codes.includes(code), check, ...overrides }}>{children}</AdminAccessContext>;
}
beforeEach(() => check.mockReset());
describe('审计入口隔离单元', () => {
  it('守卫只按 audit:read 放行，不依赖身份目录与空间读取', () => {
    render(withAccess(<AdminGuard code={perm.auditRead} title="审计查询"><span>允许读取审计</span></AdminGuard>, [perm.auditRead], { spaceStatus: 'unavailable' }));
    expect(screen.getByText('允许读取审计')).toBeTruthy(); expect(check).toHaveBeenCalledTimes(1);
  });
  it.each(['identity:audit:read', 'ROLE_ADMIN', 'audit:*', 'identity:user:write', 'embedding:rebuild'])('资格 %s 不替代审计读取', code => {
    render(withAccess(<AdminGuard code={perm.auditRead} title="审计查询"><span>不应加载</span></AdminGuard>, [code]));
    expect(screen.queryByText('不应加载')).toBeNull(); expect(screen.getByText('当前账号没有此管理页面的读取资格。')).toBeTruthy();
  });
  it.each(['loading', 'unavailable'] as const)('资格 %s 不挂载页面', status => {
    render(withAccess(<AdminGuard code={perm.auditRead} title="审计查询"><span>不应加载</span></AdminGuard>, [perm.auditRead], { status }));
    expect(screen.queryByText('不应加载')).toBeNull();
  });
  it('导航在资格变化后显示或移除审计入口', () => {
    const content = <MemoryRouter initialEntries={['/admin/audits']}><AdminNavigation /></MemoryRouter>;
    const view = render(withAccess(content, [perm.auditRead])); expect(screen.getByRole('link', { name: /审计查询/ }).getAttribute('href')).toBe('/admin/audits');
    view.rerender(withAccess(content, ['identity:audit:read'])); expect(screen.queryByRole('link', { name: /审计查询/ })).toBeNull();
  });
  it('安全返回函数只接受精确页面路径', () => {
    expect(safeReturnPath('/admin/audits')).toBe('/admin/audits');
    for (const path of ['/admin/audits/', '/admin/audits/1', '/admin/audits?loginUsername=private', '/admin/audits#private', 'https://outside.test/admin/audits', '//admin/audits']) expect(safeReturnPath(path)).toBe('/documents');
  });
});

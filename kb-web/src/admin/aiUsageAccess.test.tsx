import { useEffect, type ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { safeReturnPath } from '../navigation';
import { AdminAccessContext, AdminGuard, AdminNavigation, emptyAdminAccess, perm, type AdminAccess } from './access';
const check = vi.fn(), read = vi.fn();
function Child() { useEffect(() => { read(); }, []); return <span>AI 页面资料</span>; }
function context(children: ReactNode, codes: string[], override: Partial<AdminAccess> = {}) {
  return <AdminAccessContext value={{ ...emptyAdminAccess, status: 'ready', spaceStatus: 'unavailable', has: code => codes.includes(code), check, ...override }}>{children}</AdminAccessContext>;
}
beforeEach(() => { check.mockReset(); read.mockReset(); });
describe('AI 独立权限路由', () => {
  it.each([perm.aiUsageRead, perm.aiAuditRead])('单项 %s 可用且不依赖目录，另一项不挂载资料', code => {
    const other = code === perm.aiUsageRead ? perm.aiAuditRead : perm.aiUsageRead;
    const view = render(context(<AdminGuard code={code} title="AI"><Child /></AdminGuard>, [code]));
    expect(read).toHaveBeenCalledTimes(1); expect(screen.getByText('AI 页面资料')).toBeTruthy();
    view.rerender(context(<AdminGuard code={other} title="AI"><Child /></AdminGuard>, [code])); expect(screen.queryByText('AI 页面资料')).toBeNull(); expect(read).toHaveBeenCalledTimes(1);
  });
  it.each(['ROLE_ADMIN', 'audit:read', 'ai:*'])('%s 不替代精确读取资格', code => {
    render(context(<AdminGuard code={perm.aiUsageRead} title="AI"><Child /></AdminGuard>, [code])); expect(read).not.toHaveBeenCalled();
  });
  it('权限目录故障不挂载查询，导航仅显示对应页面', () => {
    const view = render(context(<AdminGuard code={perm.aiAuditRead} title="AI"><Child /></AdminGuard>, [perm.aiAuditRead], { status: 'unavailable' }));
    expect(read).not.toHaveBeenCalled(); view.rerender(context(<MemoryRouter><AdminNavigation /></MemoryRouter>, [perm.aiUsageRead]));
    expect(screen.getByRole('link', { name: /AI 用量/ }).getAttribute('href')).toBe('/admin/ai/usage'); expect(screen.queryByRole('link', { name: /AI 调用审计/ })).toBeNull();
  });
  it('安全返回地址只包含两条固定页面，不携带筛选或详情', () => {
    for (const path of ['/admin/ai/usage', '/admin/ai/audits']) expect(safeReturnPath(path)).toBe(path);
    for (const path of ['/admin/ai', '/admin/ai/usage/', '/admin/ai/audits/id', '/admin/ai/usage?userId=9', '/admin/ai/audits#callId', '//admin/ai/usage']) expect(safeReturnPath(path)).toBe('/documents');
  });
});

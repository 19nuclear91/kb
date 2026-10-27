import { useEffect, type ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { safeReturnPath } from '../navigation';
import { Session } from '../session';
import { SessionProvider } from '../SessionProvider';
import { json, me, storage } from '../test/fixtures';
import { AdminAccessContext, AdminAccessProvider, AdminGuard, AdminNavigation, emptyAdminAccess, perm } from './access';
const read = vi.fn();
function Child() { useEffect(() => { read(); }, []); return <span>报表资料</span>; }
function context(children: ReactNode, codes: string[], status: 'ready' | 'loading' | 'unavailable' = 'ready') {
  return <AdminAccessContext value={{ ...emptyAdminAccess, status, spaceStatus: 'unavailable', has: code => codes.includes(code) }}>{children}</AdminAccessContext>;
}
describe('运营报表独立访问', () => {
  it('仅有报表权限时空间失败不阻断，导航不扩张 AI 权限', () => {
    render(context(<MemoryRouter><AdminNavigation /><AdminGuard code={perm.operationsReportsRead} title="概览"><Child /></AdminGuard></MemoryRouter>, [perm.operationsReportsRead]));
    expect(screen.getByText('报表资料')).toBeTruthy(); expect(screen.getByRole('link', { name: /运营概览/ }).getAttribute('href')).toBe('/admin/reports/operations');
    expect(screen.queryByRole('link', { name: /AI 用量/ })).toBeNull();
  });
  it.each(['ROLE_ADMIN', 'ai:usage:read', 'ai:audit:read', 'audit:read', 'space:manage-all', 'operations:reports:*'])('%s 不开放报表，但原 AI 入口保持独立', code => {
    render(context(<MemoryRouter><AdminNavigation /><AdminGuard code={perm.operationsReportsRead} title="概览"><Child /></AdminGuard></MemoryRouter>, [code]));
    expect(screen.queryByText('报表资料')).toBeNull(); expect(screen.queryByRole('link', { name: /运营概览/ })).toBeNull();
    if (code === 'ai:usage:read') expect(screen.getByRole('link', { name: /AI 用量/ }).getAttribute('href')).toBe('/admin/ai/usage');
  });
  it.each(['loading', 'unavailable'] as const)('权限 %s 不挂载统计读取', status => {
    render(context(<AdminGuard code={perm.operationsReportsRead} title="概览"><Child /></AdminGuard>, [perm.operationsReportsRead], status)); expect(screen.queryByText('报表资料')).toBeNull();
  });
  it('报表路由的权限提供者不预取空间、身份或 AI 目录', async () => {
    const transport = vi.fn((path: RequestInfo | URL) => Promise.resolve(String(path) === '/api/auth/me' ? json(me) : String(path) === '/api/auth/permissions' ? json([perm.operationsReportsRead]) : new Response(null, { status: 503 })));
    const session = new Session(storage(), transport as typeof fetch);
    render(<SessionProvider session={session}><AdminAccessProvider loadSpaces={false}><AdminGuard code={perm.operationsReportsRead} title="概览"><Child /></AdminGuard></AdminAccessProvider></SessionProvider>);
    await waitFor(() => expect(screen.getByText('报表资料')).toBeTruthy());
    expect(transport.mock.calls.every(([path]) => ['/api/auth/me', '/api/auth/permissions'].includes(String(path)))).toBe(true);
  });
  it('登录返回仅接受固定页，不接受筛选、子路径或外链', () => {
    expect(safeReturnPath('/admin/reports/operations')).toBe('/admin/reports/operations');
    for (const path of ['/admin/reports/operations?from=private', '/admin/reports/operations#private', '/admin/reports/operations/7', '/admin/reports/operations/', '//admin/reports/operations', 'https://outside.test/admin/reports/operations'])
      expect(safeReturnPath(path)).toBe('/documents');
  });
});
